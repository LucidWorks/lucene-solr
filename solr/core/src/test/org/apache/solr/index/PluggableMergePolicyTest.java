package org.apache.solr.index;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.MergeTrigger;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.servlet.SolrDispatchFilter;
import org.apache.solr.util.RefCounted;
import org.junit.Test;

/**
 *
 */
public class PluggableMergePolicyTest extends AbstractFullDistribZkTestBase {

  public static class CustomMergePolicyFactory extends MergePolicyFactory {

    public CustomMergePolicyFactory(SolrResourceLoader resourceLoader, MergePolicyFactoryArgs args, IndexSchema schema) {
      super(resourceLoader, args, schema);
    }

    @Override
    public MergePolicy getMergePolicy() {
      return new CustomMergePolicy();
    }
  }

  public static class CustomMergePolicy extends MergePolicy {

    @Override
    public MergeSpecification findMerges(MergeTrigger mergeTrigger, SegmentInfos segmentInfos, IndexWriter writer) throws IOException {
      return null;
    }

    @Override
    public MergeSpecification findForcedMerges(SegmentInfos segmentInfos, int maxSegmentCount, Map<SegmentCommitInfo, Boolean> segmentsToMerge, IndexWriter writer) throws IOException {
      return null;
    }

    @Override
    public MergeSpecification findForcedDeletesMerges(SegmentInfos segmentInfos, IndexWriter writer) throws IOException {
      return null;
    }
  }

  @Override
  protected String getCloudSolrConfig() {
    return "solrconfig-pluggablemergepolicyfactory.xml";
  }

  @Test
  public void testConfigChanges() throws Exception {
    String collectionName = "pluggableMPF_test";
    CollectionAdminRequest.Create createCollectionRequest = new CollectionAdminRequest.Create()
        .setCollectionName(collectionName)
        .setNumShards(2)
        .setReplicationFactor(1)
        .setConfigName("conf1");
    CollectionAdminResponse response = createCollectionRequest.process(cloudClient);
    assertEquals(0, response.getStatus());
    assertTrue(response.isSuccess());
    Thread.sleep(5000);

    cloudClient.setDefaultCollection(collectionName);
    Map<String, Object> pluginProps = new HashMap<>();
    pluginProps.put(FieldType.CLASS_NAME, UninvertDocValuesMergePolicyFactory.class.getName());
    String propValue = Utils.toJSONString(pluginProps);
    CollectionAdminRequest.ClusterProp clusterProp = new CollectionAdminRequest.ClusterProp()
        .setPropertyName(PluggableMergePolicyFactory.DEFAULT_POLICY_PROP)
        .setPropertyValue(propValue);
    clusterProp.process(cloudClient);

    // no reload -> still using the default MP.
    // get any core and verify its MergePolicy

    SolrCore core = getAnyCore(collectionName);
    if (core == null) {
      fail("can't find any core belonging to the collection " + collectionName);
    }
    RefCounted<IndexWriter> writerRef = core.getUpdateHandler().getSolrCoreState().getIndexWriter(null);
    try {
      IndexWriter iw = writerRef.get();
      MergePolicy mp = iw.getConfig().getMergePolicy();
      assertEquals("default merge policy", TieredMergePolicy.class.getName(), mp.getClass().getName());
    } finally {
      writerRef.decref();
    }


    // reloaded cores will be more recent that this time
    Map<String, Long> urlToTimeBefore = new HashMap<>();
    collectStartTimes(collectionName, cloudClient, urlToTimeBefore);

    // reload the collection
    CollectionAdminRequest<CollectionAdminRequest.Reload> reload = new CollectionAdminRequest.Reload()
        .setCollectionName(collectionName);
    reload.process(cloudClient);

    boolean reloaded = waitForReloads(collectionName, cloudClient, urlToTimeBefore);
    assertTrue("not reloaded in time", reloaded);

    UpdateRequest ureq = new UpdateRequest();
    for (int i = 100; i < 200; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", i);
      ureq.add(doc);
    }
    ureq.process(cloudClient);
    cloudClient.commit();


    core = getAnyCore(collectionName);
    if (core == null) {
      fail("can't find any reloaded core belonging to the collection " + collectionName);
    }
    writerRef = core.getUpdateHandler().getSolrCoreState().getIndexWriter(null);
    try {
      IndexWriter iw = writerRef.get();
      MergePolicy mp = iw.getConfig().getMergePolicy();
      assertEquals("custom merge policy", OneMergeWrappingMergePolicy.class.getName(), mp.getClass().getName());
    } finally {
      writerRef.decref();
    }

    // set collection-specific plugin
    pluginProps.clear();
    pluginProps.put(FieldType.CLASS_NAME, CustomMergePolicyFactory.class.getName());
    propValue = Utils.toJSONString(pluginProps);
    clusterProp = new CollectionAdminRequest.ClusterProp()
        .setPropertyName(PluggableMergePolicyFactory.MERGE_POLICY_PROP + collectionName)
        .setPropertyValue(propValue);
    clusterProp.process(cloudClient);

    // reload
    urlToTimeBefore.clear();
    collectStartTimes(collectionName, cloudClient, urlToTimeBefore);
    reload.process(cloudClient);

    reloaded = waitForReloads(collectionName, cloudClient, urlToTimeBefore);
    assertTrue("not reloaded in time", reloaded);
    core = getAnyCore(collectionName);
    if (core == null) {
      fail("can't find any reloaded core belonging to the collection " + collectionName);
    }
    writerRef = core.getUpdateHandler().getSolrCoreState().getIndexWriter(null);
    try {
      IndexWriter iw = writerRef.get();
      MergePolicy mp = iw.getConfig().getMergePolicy();
      assertEquals("custom merge policy", CustomMergePolicy.class.getName(), mp.getClass().getName());
    } finally {
      writerRef.decref();
    }


  }

  private SolrCore getAnyCore(String collectionName) {
    SolrCore core = null;
    for (JettySolrRunner jetty : jettys) {
      CoreContainer cores = ((SolrDispatchFilter)jetty.getDispatchFilter().getFilter()).getCores();
      Iterator<SolrCore> solrCores = cores.getCores().iterator();
      while (solrCores.hasNext()) {
        SolrCore c = solrCores.next();
        if (c.getCoreDescriptor().getCloudDescriptor() != null &&
            c.getCoreDescriptor().getCloudDescriptor().getCollectionName().equals(collectionName)) {
          core = c;
          break;
        }
      }
      if (core != null) {
        break;
      }
    }
    return core;
  }

}
