package org.apache.solr.schema;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.carrotsearch.randomizedtesting.rules.SystemPropertiesRestoreRule;
import org.apache.commons.collections.MapUtils;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.util.NumericUtils;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.index.AddDocValuesMergePolicyFactory;
import org.apache.solr.index.MergePolicyFactory;
import org.apache.solr.index.MergePolicyFactoryArgs;
import org.apache.solr.index.UninvertDocValuesMergePolicyFactory;
import org.apache.solr.util.RefCounted;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

public class AddDocValuesMergePolicyTest extends SolrTestCaseJ4 {

  @Rule
  public TestRule solrTestRules = RuleChain.outerRule(new SystemPropertiesRestoreRule());

// Fields available in schema-rest.xml
// int, float, long, double, tint, tfloat, tlong, tdouble, string

  //TODO if this is moved upstream, deal with points based fields.
  @BeforeClass
  public static void beforeClass() throws Exception {
    System.setProperty("managed.schema.mutable", "true");
    System.setProperty("managed.schema.resource.name", "schema-tiny.xml");
    initCore("solrconfig-managed-schema.xml", "schema-tiny.xml");
    setupAllFields();
  }

  private static Map<String, String> fieldMap = MapUtils.putAll(new HashMap<>(), new Object[][]{
      {"str_sv", "string"},
      {"str_mv", "string"},
      {"int_sv", "tint"},
      {"int_mv", "tint"},
      {"double_sv", "tdouble"},
      {"double_mv", "tdouble"},
      {"long_sv", "tlong"},
      {"long_mv", "tlong"},
      {"float_sv", "tfloat"},
      {"float_mv", "tfloat"},
      {"_version_", "tlong"},
      {"id", "string"}
  });

  private static int MV_INCREMENT = 1_000_000;

  private static void setupAllFields() throws IOException {
    assertTrue("Must have a mutable schema", h.getCore().getLatestSchema() instanceof ManagedIndexSchema);
    ManagedIndexSchema schema = (ManagedIndexSchema) h.getCore().getLatestSchema();

    // Add all the field types in one go:
    List<FieldType> ftList = new ArrayList<>();
    Set<String> types = new HashSet<>();
    types.addAll(fieldMap.values());
    for (String type : types) {
      if (type.equals("string")) continue;
      ftList.add(getFieldType(schema, type));
    }
    schema = schema.addFieldTypes(ftList, false);
    h.getCore().setLatestSchema(schema);

    Map<String, ?> svOpts = MapUtils.putAll(new HashMap<>(), new Object[][]{
        {"indexed", true},
        {"stored", false},
        {"docValues", false},
    });

    Map<String, ?> mvOpts = MapUtils.putAll(new HashMap<>(), new Object[][]{
        {"indexed", true},
        {"stored", false},
        {"multiValued", true},
        {"docValues", false},
    });


    schema = schema.deleteDynamicFields(new ArrayList<>(Arrays.asList("*_t", "*")));
    h.getCore().setLatestSchema(schema);

    List<SchemaField> fieldsToAdd = new ArrayList<>();
    for (Map.Entry<String, String> ent : fieldMap.entrySet()) {
      if (ent.getKey().equals("id")) {
        schema = schema.replaceField(ent.getKey(), schema.getFieldTypeByName(ent.getValue()), svOpts);
        continue;
      }
      if (ent.getKey().endsWith("_sv") || ent.getKey().equals("_version_")) {
        fieldsToAdd.add(schema.newField(ent.getKey(), ent.getValue(), svOpts));
      } else {
        fieldsToAdd.add(schema.newField(ent.getKey(), ent.getValue(), mvOpts));
      }
    }

    schema = schema.addFields(fieldsToAdd, null, false);
    h.getCore().setLatestSchema(schema);
  }

  private void addDoc(int id) {
    String idVal = String.valueOf(id);
    String idVal1M = String.valueOf(id + MV_INCREMENT);
    SolrInputDocument doc = new SolrInputDocument();

    for (String field : fieldMap.keySet()) {
      if (field.equals("_version_")) {
        continue;
      }
      doc.addField(field, idVal);
      if (field.endsWith("_mv")) {
        doc.addField(field, idVal1M);
      } else if (field.endsWith("_sv") == false && field.equals("id") == false) {
        fail("Did not recognize field " + field);
      }
    }
    assertU(adoc(doc));
  }

  // The general outline here is:
  // Index a bunch of docs without docValues, we don't care about merging here
  // Change the schema to add docValues to the fields
  // Add some more documents with docValues
  // Verify that every new segment has docValues for every field for every document. This might include newly-merged segments
  // optimize
  // verify that _all_ the original segments written without docValues are gone
  // verify that none of the segments that already had docValues entries were merged away
  // verify that there are the exact number of segments before and after, all merges should be singletons
  // verify that all the segments have docValues for every field in every document
  @Test
  public void testRewriteAllSegments() throws Exception {
    // Index a bunch of docs without docValues, we don't care about merging here
    final int numDocs = atLeast(100);
    int counter = 0;
    while (counter++ < numDocs) {
      addDoc(counter);
      // Insure at least some runs have multiple segments.
      if ((random().nextInt(100) % 20) == 0) {
        assertU(commit());
      }
    }

    assertU(commit());

    SegmentInfos segInfosWithoutDV = getSegmentInfos();
    verifySegmentsDVStatus(segInfosWithoutDV, false);

    // Change the schema to add docValues to the fields


    Map<String, ?> svOpts = MapUtils.putAll(new HashMap<>(), new Object[][]{
        {"indexed", true},
        {"stored", false},
        {"docValues", true}
    });

    Map<String, ?> mvOpts = MapUtils.putAll(new HashMap<>(), new Object[][]{
        {"indexed", true},
        {"stored", false},
        {"multiValued", true},
        {"docValues", true}
    });

    ManagedIndexSchema schema = (ManagedIndexSchema) h.getCore().getLatestSchema();
    for (Map.Entry<String, String> ent : fieldMap.entrySet()) {
      if (ent.getKey().endsWith("_sv") || ent.getKey().equals("_version_") || ent.getKey().equals("id")) {
        schema = schema.replaceField(ent.getKey(), schema.getFieldTypeByName(ent.getValue()), svOpts);
      } else if (ent.getKey().endsWith("_mv")) {
        schema = schema.replaceField(ent.getKey(), schema.getFieldTypeByName(ent.getValue()), mvOpts);
      } else {
        fail("Unrecognized field");
      }
    }

    h.getCore().setLatestSchema(schema);
    schema = (ManagedIndexSchema) h.getCore().getLatestSchema();

    // Add some more documents with docValues

    final int lim = counter + atLeast(100);
    while (counter++ < lim) {
      addDoc(counter);
      // Insure at least some runs have multiple segments.
      if ((random().nextInt(100) % 20) == 0) {
        assertU(commit());
      }
    }
    assertU(commit());

    // Verify that every new segment has docValues for every field for every document

    SegmentInfos segInfosBeforeOptimize = getSegmentInfos();
    SegmentInfos segOriginalDV = removeSegs(segInfosBeforeOptimize, segInfosWithoutDV);

    assertTrue("We should have at least some segments with docValues", segOriginalDV.size() > 0);

    // Verify that every new segment has docValues for every field for every document. This might include newly-merged segments
    verifySegmentsDVStatus(segOriginalDV, true);

    // optimize
    rewriteSegmentsWithDV();

    // verify that _all_ the original segments written without docValues are gone
    SegmentInfos segInfosAfterOptimize = getSegmentInfos();
    SegmentInfos testSegs = removeSegs(segInfosWithoutDV, segInfosAfterOptimize);
    assertEquals("There should be no original segments left", testSegs.size(), segInfosWithoutDV.size());

    // verify that none of the segments that already had docValues entries were merged away
    testSegs = removeSegs(segOriginalDV, segInfosAfterOptimize);
    assertEquals("Segments originally written with docValues should NOT have been merged", testSegs.size(), 0);

    // verify that there are the exact number of segments before and after, all merges should be singletons
    assertEquals("All merges should be singleton merges, so counts should match",
        segInfosAfterOptimize.size(), segInfosAfterOptimize.size());

    // verify that all the new segments have docValues for every field in every document
    verifySegmentsDVStatus(segInfosAfterOptimize, true);

  }

  private SegmentInfos removeSegs(SegmentInfos removeFrom, SegmentInfos removeThese) {
    SegmentInfos ret = new SegmentInfos();

    Set<String> removeNames = new HashSet<>();
    removeThese.asList().stream()
        .forEach(seg -> removeNames.add(seg.info.name));

    removeFrom.asList().stream().filter(seg -> removeNames.contains(seg.info.name) == false)
        .forEach(seg -> ret.add(seg));

    return ret;
  }

  private void rewriteSegmentsWithDV() throws IOException {
    IndexWriterConfig iwc = newIndexWriterConfig();
    UninvertDocValuesMergePolicyFactory mpfU = new UninvertDocValuesMergePolicyFactory(h.getCore().getResourceLoader(),
        new MergePolicyFactoryArgs(), h.getCore().getLatestSchema());

    MergePolicyFactory mpfR = new AddDocValuesMergePolicyFactory(h.getCore().getResourceLoader(),
        new MergePolicyFactoryArgs(), h.getCore().getLatestSchema());
    iwc.setMergePolicy(mpfU.getMergePolicyInstance(mpfR.getMergePolicy()));

    Path iPath = Paths.get(h.getCore().getIndexDir());

    try (Directory dir = newFSDirectory(iPath); IndexWriter writer = new IndexWriter(dir, iwc)) {
      writer.forceMerge(Integer.MAX_VALUE, true);
    }
  }

  private void checkDVs(SegmentCommitInfo info, Set<String> fields, SegmentReader reader, boolean checkDvVals) throws IOException {

    // First check all the fields for having docValues at all
    for (String field : fields) {
      FieldInfo fi = reader.getFieldInfos().fieldInfo(field);
      if (checkDvVals == false && fi.getDocValuesType() != DocValuesType.NONE) {
        fail("Segment " + info.toString() + " SHOULD NOT have DV values set for field " + field);
      }
      if (checkDvVals && fi.getDocValuesType() == DocValuesType.NONE) {
        fail("Segment " + info.toString() + " SHOULD NOT have DV values set for field " + field);
      }
    }
    if (checkDvVals == false) {
      return;
    }

    DocValuesProducer dvp = reader.getDocValuesReader();
    // Check that each dv entry has the expected value
    for (int idx = 0; idx < reader.maxDoc(); ++idx) {
      // Get the value that the rest of the fields are keyed from.
      FieldInfo fi = reader.getFieldInfos().fieldInfo("int_sv");
      final Long key = reader.getDocValuesReader().getNumeric(fi).get(idx);
      final Long key1M = key + MV_INCREMENT;

      String sVal;
      long ord;
      SortedSetDocValues sdv;

      for (String field : fieldMap.keySet()) {
        fi = reader.getFieldInfos().fieldInfo(field);
        switch (field) {
          case "str_sv":
          case "id":
            sVal = dvp.getSorted(fi).get(idx).utf8ToString();
            assertEquals("Found unexpected value ", sVal, Long.toString(key));
            break;

          case "str_mv":
            sdv = dvp.getSortedSet(fi);
            sdv.setDocument(idx);

            while ((ord = sdv.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
              sVal = sdv.lookupOrd(ord).utf8ToString();
              //sVal = ((SortedDocValues)dvp).get(idx).utf8ToString();
              assertTrue("Unexpected string value ",
                  sVal.equals(Long.toString(key)) || sVal.equals(Long.toString(key1M)));
            }
            break;

          case "int_sv":
            assertEquals("Unexpected int value ", dvp.getNumeric(fi).get(idx), key.longValue());
            break;

          case "long_sv":
            assertEquals("Unexpected int value ", dvp.getNumeric(fi).get(idx), key.longValue());
            break;

          case "double_sv":
            double d = NumericUtils.sortableLongToDouble(dvp.getNumeric(fi).get(idx));
            assertEquals("Unexpected int value ", d, key.doubleValue(), 0.001);
            break;

          case "float_sv":
            float f = NumericUtils.sortableIntToFloat((int) dvp.getNumeric(fi).get(idx));
            assertEquals("Unexpected int value ", f, key.floatValue(), 0.001);
            break;

          case "int_mv":
            sdv = dvp.getSortedSet(fi);
            sdv.setDocument(idx);

            while ((ord = sdv.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
              final long test = NumericUtils.prefixCodedToInt(sdv.lookupOrd(ord));
              assertTrue("Found unexpected multivalued inteber value", key == test || key1M == test);
            }
            break;

          case "long_mv":
            sdv = dvp.getSortedSet(fi);
            sdv.setDocument(idx);

            while ((ord = sdv.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
              final long test = NumericUtils.prefixCodedToLong(sdv.lookupOrd(ord));
              assertTrue("Found unexpected multivalued inteber value", key == test || key1M == test);
            }
            break;

          case "double_mv":
            sdv = dvp.getSortedSet(fi);
            sdv.setDocument(idx);

            while ((ord = sdv.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
              final double test = NumericUtils.sortableLongToDouble(NumericUtils.prefixCodedToLong(sdv.lookupOrd(ord)));
              assertTrue("Found unexpected multivalued inteber value", key == test || key1M == test);
            }
            break;

          case "float_mv":
            sdv = dvp.getSortedSet(fi);
            sdv.setDocument(idx);

            while ((ord = sdv.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
              final float test = NumericUtils.sortableIntToFloat(NumericUtils.prefixCodedToInt(sdv.lookupOrd(ord)));
              assertTrue("Found unexpected multivalued inteber value", test == test || key1M == test);
            }
            break;
          case "_version_":
            break;

          default:
            fail("Unrecognized field " + field);
        }
      }
    }
  }

  private void verifySegmentsDVStatus(SegmentInfos infos, boolean checkDvVals) throws IOException {
    for (SegmentCommitInfo info : infos) {
      try (SegmentReader reader = new SegmentReader(info, IOContext.DEFAULT)) {
        checkDVs(info, fieldMap.keySet(), reader, checkDvVals);
      }
    }
  }

  private SegmentInfos getSegmentInfos() throws IOException {
    RefCounted<IndexWriter> writerRef = h.getCore().getUpdateHandler().getSolrCoreState().getIndexWriter(null);
    try {
      IndexWriter iw = writerRef.get();
      return SegmentInfos.readLatestCommit(iw.getDirectory());
    } finally {
      writerRef.decref();
    }
  }

  private static Map<String, String> stringProps =
      MapUtils.putAll(new HashMap<>(), new String[][]{
          {"precisionStep", "8"},
          {"class", "solr.StrField"},
          {"typeName", "string"}
      });

  private static Map<String, String> tintProps =
      MapUtils.putAll(new HashMap<>(), new String[][]{
          {"precisionStep", "8"},
          {"class", "solr.TrieIntField"},
          {"name", "tint"}
      });
  private static Map<String, String> tlongProps =
      MapUtils.putAll(new HashMap<>(), new String[][]{
          {"precisionStep", "8"},
          {"class", "solr.TrieLongField"},
          {"name", "tlong"}
      });

  private static Map<String, String> tfloatProps =
      MapUtils.putAll(new HashMap<>(), new String[][]{
          {"precisionStep", "8"},
          {"class", "solr.TrieFloatField"},
          {"name", "tfloat"}
      });

  private static Map<String, String> tdoubleProps =
      MapUtils.putAll(new HashMap<>(), new String[][]{
          {"precisionStep", "8"},
          {"class", "solr.TrieDoubleField"},
          {"name", "tdouble"}
      });

  private static FieldType getFieldType(IndexSchema schema, String fieldType) {
    FieldType type = null;
    switch (fieldType) {
      case "string":
        type = new StrField();
        type.init(schema, stringProps);
        break;
      case "tint":
        type = new TrieIntField();
        type.init(schema, tintProps);
        break;
      case "tdouble":
        type = new TrieDoubleField();
        type.init(schema, tdoubleProps);
        break;
      case "tlong":
        type = new TrieLongField();
        type.init(schema, tlongProps);
        break;
      case "tfloat":
        type = new TrieFloatField();
        type.init(schema, tfloatProps);
        break;
      default:
        fail("Unknown field's type being asked for: " + fieldType);
    }
    type.setTypeName(fieldType);
    return type;
  }
}
