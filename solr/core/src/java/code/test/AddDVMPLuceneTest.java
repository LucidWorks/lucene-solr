package code.test;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.uninverting.UninvertingReader;
import org.apache.lucene.util.Bits;
import org.apache.solr.index.AddDocValuesMergePolicyFactory;

/**
 * Part of stand-alone test for externally beating up changing docValues while indexing
 */
public class AddDVMPLuceneTest {

  private static final String TEST_PATH = "/Users/ab/tmp/addvtest";
  private static final Path testPath = Paths.get(TEST_PATH);
  private static final String TEST_FIELD = "test";

  private static final FieldType noDV = new FieldType();
  private static final FieldType dv = new FieldType();

  static {
    noDV.setIndexOptions(IndexOptions.DOCS);
    dv.setIndexOptions(IndexOptions.DOCS);
  }

  public static void main(String[] args) throws Exception {
    AddDVMPLuceneTest test = new AddDVMPLuceneTest();
    test.doTest();
  }

  static final String[] facets = new String[] {
      "aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj"
  };
  private final Map<String, UninvertingReader.Type> mapping = new ConcurrentHashMap<>();
  private final AtomicBoolean stopRun = new AtomicBoolean(false);

  private void cleanup() throws Exception {
    FileUtils.deleteDirectory(testPath.toFile());
  }

  private void doTest() throws Exception {
    cleanup();
    Directory d = FSDirectory.open(testPath);
    IndexWriterConfig cfg = new IndexWriterConfig(new WhitespaceAnalyzer());
    cfg.setMergePolicy(new AddDocValuesMergePolicyFactory.AddDVMergePolicy(new TieredMergePolicy(), mapping::get, null, false, true));
    cfg.setInfoStream(System.out);
    ExtIndexWriter iw = new ExtIndexWriter(d, cfg);
    for (int i = 0; i < 5; i++) {
      IndexingThread indexingThread = new IndexingThread("t" + i, iw);
      indexingThread.start();
    }
    QueryThread qt = new QueryThread(iw);
    qt.start();
    Thread.sleep(10000);
    mapping.put(TEST_FIELD, UninvertingReader.Type.INTEGER);
  }

  private static final class ExtIndexWriter extends IndexWriter {

    /**
     * Constructs a new IndexWriter per the settings given in <code>conf</code>.
     * If you want to make "live" changes to this writer instance, use
     * {@link #getConfig()}.
     *
     * <p>
     * <b>NOTE:</b> after ths writer is created, the given configuration instance
     * cannot be passed to another writer. If you intend to do so, you should
     * {@link IndexWriterConfig#clone() clone} it beforehand.
     *
     * @param d    the index directory. The index is either created or appended
     *             according <code>conf.getOpenMode()</code>.
     * @param conf the configuration settings according to which IndexWriter should
     *             be initialized.
     * @throws IOException if the directory cannot be read/written to, or if it does not
     *                     exist and <code>conf.getOpenMode()</code> is
     *                     <code>OpenMode.APPEND</code> or if there is any other low-level
     *                     IO error
     */
    public ExtIndexWriter(Directory d, IndexWriterConfig conf) throws IOException {
      super(d, conf);
    }

    public DirectoryReader getReader() throws IOException {
      flush(false, false);
      try {
        DirectoryReader reader = DirectoryReader.open(getDirectory());
        return reader;
      } catch (Throwable re) {
        return null;
      }
    }
  }

  private class QueryThread extends Thread {
    ExtIndexWriter writer;
    QueryThread(ExtIndexWriter writer) {
      this.writer = writer;
    }

    public void run() {
      while (!stopRun.get() && !Thread.interrupted()) {
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {
          return;
        }
        try {
          DirectoryReader reader = writer.getReader();
          if (reader == null) {
            System.err.println("# no reader");
            continue;
          }
          reader = UninvertingReader.wrap(reader, mapping::get);
          for (LeafReaderContext ctx : reader.leaves()) {
            checkLeaf(ctx);
          }
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }

    private void checkLeaf(LeafReaderContext ctx) throws IOException {
      LeafReader reader = ctx.reader();
      FieldInfo fi = reader.getFieldInfos().fieldInfo(TEST_FIELD);
      Bits docsWithValues = reader.getDocsWithField(TEST_FIELD);
      NumericDocValues dv = reader.getNumericDocValues(TEST_FIELD);
      int present = 0;
      for (int i = 0; i < docsWithValues.length(); i++) {
        if (docsWithValues.get(i)) {
          present++;
          Document doc = reader.document(i);
          long value = dv.get(i);
          long stringValue = Long.parseLong(doc.get(TEST_FIELD));
          if (value != stringValue) {
            throw new IOException("value mismatch, base=" + ctx.docBase + ", doc=" + i + ", string=" + stringValue + ", dv=" + value);
          }
        }
      }
      if (present < reader.numDocs()) {
        throw new IOException("count mismatch: numDocs=" + reader.numDocs() + ", present=" + present + ", reader=" + reader);
      }
    }
  }

  private class IndexingThread extends Thread {
    IndexWriter writer;
    String threadId;
    IndexingThread(String threadId, IndexWriter writer) {
      this.threadId = threadId;
      this.writer = writer;
    }


    public void run() {
      int id = 0;
      while (!stopRun.get() && !Thread.interrupted()) {
        Document d = new Document();
        Field f = new Field("id", id + "-" + threadId, TextField.TYPE_NOT_STORED);
        d.add(f);
        UninvertingReader.Type type = mapping.get(TEST_FIELD);
        if (type != null) {
          f = new NumericDocValuesField(TEST_FIELD, id);
        } else {
          f = new Field(TEST_FIELD, facets[id % 10], TextField.TYPE_NOT_STORED);
        }
        d.add(f);
        try {
          writer.addDocument(d);
          if (id > 0 && (id % 10000 == 0)) {
            System.err.println("- added " + id);
            // delete first 500
//            for (int j = id - 10000; j < id - 10000 + 500; j++) {
//              writer.deleteDocuments(new Term("id", j + "-" + threadId));
//            }
            writer.commit();
//            try {
//              Thread.sleep(50);
//            } catch (InterruptedException e) {
//              return;
//            }
          }
        } catch (IOException ioe) {
          throw new RuntimeException("writer.addDocument", ioe);
        }
        id++;
      }
    }
  }
}
