/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.beam.sdk.extensions.smb.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.bigquery.model.TableRow;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult.State;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.extensions.avro.coders.AvroCoder;
import org.apache.beam.sdk.extensions.avro.io.AvroGeneratedUser;
import org.apache.beam.sdk.extensions.avro.io.AvroIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.FlatMapElements;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.transforms.join.KeyedPCollectionTuple;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;

/** Benchmark of {@link CoGroupByKey} using data generated by {@link SinkBenchmark}. */
public class CoGroupByKeyBenchmark {

  private static ObjectMapper objectMapper = new ObjectMapper();

  /** SourceOptions. */
  public interface SourceOptions extends PipelineOptions {
    String getAvroSource();

    void setAvroSource(String value);

    String getJsonSource();

    void setJsonSource(String value);
  }

  public static void main(String[] args) {
    final SourceOptions sourceOptions =
        PipelineOptionsFactory.fromArgs(args).as(SourceOptions.class);
    System.out.println("CoGroupByKey SourceOptions=" + sourceOptions);

    final Pipeline pipeline = Pipeline.create(sourceOptions);

    final PCollection<KV<String, AvroGeneratedUser>> lhs =
        pipeline
            .apply(AvroIO.read(AvroGeneratedUser.class).from(sourceOptions.getAvroSource()))
            .apply(WithKeys.of(user -> user.getName().toString()))
            .setCoder(KvCoder.of(StringUtf8Coder.of(), AvroCoder.of(AvroGeneratedUser.class)));

    final PCollection<KV<String, TableRow>> rhs =
        pipeline
            .apply(TextIO.read().from(sourceOptions.getJsonSource()))
            .apply(
                MapElements.into(
                        TypeDescriptors.kvs(
                            TypeDescriptors.strings(), TypeDescriptor.of(TableRow.class)))
                    .via(
                        s -> {
                          try {
                            TableRow record = objectMapper.readValue(s, TableRow.class);
                            return KV.of(record.get("user").toString(), record);
                          } catch (IOException e) {
                            throw new RuntimeException(e);
                          }
                        }));

    final TupleTag<AvroGeneratedUser> tl = new TupleTag<>();
    final TupleTag<TableRow> tr = new TupleTag<>();

    KeyedPCollectionTuple.of(tl, lhs)
        .and(tr, rhs)
        .apply(CoGroupByKey.create())
        .apply(
            FlatMapElements.into(
                    TypeDescriptors.kvs(
                        TypeDescriptors.strings(),
                        TypeDescriptors.kvs(
                            TypeDescriptor.of(AvroGeneratedUser.class),
                            TypeDescriptor.of(TableRow.class))))
                .via(
                    kv -> {
                      String key = kv.getKey();
                      Iterable<AvroGeneratedUser> il = kv.getValue().getAll(tl);
                      Iterable<TableRow> ir = kv.getValue().getAll(tr);
                      List<KV<String, KV<AvroGeneratedUser, TableRow>>> output = new ArrayList<>();
                      for (AvroGeneratedUser l : il) {
                        for (TableRow r : ir) {
                          output.add(KV.of(key, KV.of(l, r)));
                        }
                      }
                      return output;
                    }))
        .apply(Count.globally())
        .apply(
            MapElements.into(TypeDescriptors.longs())
                .via(
                    c -> {
                      System.out.println("Global count = " + c);
                      return c;
                    }));

    final long startTime = System.currentTimeMillis();
    final State state = pipeline.run().waitUntilFinish();
    System.out.println(
        String.format(
            "CoGroupByKeyBenchmark finished with state %s in %d ms",
            state, System.currentTimeMillis() - startTime));
  }
}
