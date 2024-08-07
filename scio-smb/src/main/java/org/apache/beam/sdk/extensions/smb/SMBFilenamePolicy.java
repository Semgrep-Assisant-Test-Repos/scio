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

package org.apache.beam.sdk.extensions.smb;

import java.io.Serializable;
import java.util.UUID;
import org.apache.beam.sdk.io.fs.ResolveOptions.StandardResolveOptions;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.DisplayData.Builder;
import org.apache.beam.sdk.transforms.display.HasDisplayData;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/**
 * Naming policy for SMB files, similar to {@link
 * org.apache.beam.sdk.io.FileBasedSink.FilenamePolicy}.
 *
 * <p>File names are assigned uniquely per {@link BucketShardId}. This class functions differently
 * for the initial write to temp files, and the move of those files to their final destination. This
 * is because temp writes need to be idempotent in case of bundle failure, and are thus timestamped
 * to ensure an uncorrupted write result when a bundle succeeds.
 */
public final class SMBFilenamePolicy implements Serializable {

  private static final String TEMP_DIRECTORY_PREFIX = ".temp-beam";
  private final String tempId = UUID.randomUUID().toString();

  private final ResourceId directory;
  private final String filenamePrefix;
  private final String filenameSuffix;

  public SMBFilenamePolicy(ResourceId directory, String filenamePrefix, String filenameSuffix) {
    Preconditions.checkArgument(directory.isDirectory(), "ResourceId must be a directory");
    this.directory = directory;
    this.filenamePrefix = filenamePrefix;
    this.filenameSuffix = filenameSuffix;
  }

  public FileAssignment forDestination() {
    return new FileAssignment(directory, filenamePrefix, filenameSuffix, false);
  }

  FileAssignment forTempFiles(ResourceId tempDirectory) {
    final String tempDirName = String.format(TEMP_DIRECTORY_PREFIX + "-%s", getTempId());
    return new FileAssignment(
        tempDirectory
            .getCurrentDirectory()
            .resolve(tempDirName, StandardResolveOptions.RESOLVE_DIRECTORY),
        filenamePrefix,
        filenameSuffix,
        true);
  }

  @VisibleForTesting
  String getTempId() {
    return tempId;
  }

  /**
   * A file name assigner based on a specific output directory and file suffix. Optionally prepends
   * a timestamp to file names to ensure idempotence.
   */
  public static class FileAssignment implements Serializable, HasDisplayData {

    private static final String NULL_KEYS_BUCKET_TEMPLATE = "null-keys";
    private static final String NUMERIC_BUCKET_TEMPLATE = "%05d-of-%05d";
    private static final String METADATA_FILENAME = "metadata.json";
    private static final DateTimeFormatter TEMPFILE_TIMESTAMP =
        DateTimeFormat.forPattern("yyyy-MM-dd_HH-mm-ss-");

    private final String bucketOnlyTemplate;
    private final String bucketShardTemplate;

    private final ResourceId directory;
    private final String filenameSuffix;
    private final boolean doTimestampFiles;

    FileAssignment(
        ResourceId directory,
        String filenamePrefix,
        String filenameSuffix,
        boolean doTimestampFiles) {
      this.directory = directory;
      this.filenameSuffix = filenameSuffix;
      this.doTimestampFiles = doTimestampFiles;

      bucketOnlyTemplate = filenamePrefix + "-%s%s";
      bucketShardTemplate = filenamePrefix + "-%s-shard-%05d-of-%05d%s";
    }

    ResourceId forBucket(BucketShardId id, int maxNumBuckets, int maxNumShards) {
      Preconditions.checkArgument(
          id.getBucketId() < maxNumBuckets,
          "Can't assign a filename for bucketShardId %s: max number of buckets is %s",
          id,
          maxNumBuckets);

      Preconditions.checkArgument(
          id.getShardId() < maxNumShards,
          "Can't assign a filename for bucketShardId %s: max number of shards is %s",
          id,
          maxNumShards);

      final String bucketName =
          id.isNullKeyBucket()
              ? NULL_KEYS_BUCKET_TEMPLATE
              : String.format(NUMERIC_BUCKET_TEMPLATE, id.getBucketId(), maxNumBuckets);

      final String timestamp = doTimestampFiles ? Instant.now().toString(TEMPFILE_TIMESTAMP) : "";
      String filename =
          maxNumShards == 1 || id.isNullKeyBucket()
              ? String.format(bucketOnlyTemplate, bucketName, filenameSuffix)
              : String.format(
                  bucketShardTemplate, bucketName, id.getShardId(), maxNumShards, filenameSuffix);

      return directory.resolve(timestamp + filename, StandardResolveOptions.RESOLVE_FILE);
    }

    public ResourceId forBucket(BucketShardId id, BucketMetadata<?, ?, ?> metadata) {
      return forBucket(id, metadata.getNumBuckets(), metadata.getNumShards());
    }

    public ResourceId forMetadata() {
      String timestamp = doTimestampFiles ? Instant.now().toString(TEMPFILE_TIMESTAMP) : "";
      return directory.resolve(timestamp + METADATA_FILENAME, StandardResolveOptions.RESOLVE_FILE);
    }

    /** Returns a ResourceId matching the null keys. */
    public ResourceId forNullKeys() {
      return directory.resolve(
          NULL_KEYS_BUCKET_TEMPLATE + "*" + filenameSuffix, StandardResolveOptions.RESOLVE_FILE);
    }

    public ResourceId getDirectory() {
      return directory;
    }

    @Override
    public void populateDisplayData(Builder builder) {
      builder.add(DisplayData.item("directory", directory.toString()));
      builder.add(DisplayData.item("filenameSuffix", filenameSuffix));
    }

    public static ResourceId forDstMetadata(ResourceId directory) {
      return directory.resolve(METADATA_FILENAME, StandardResolveOptions.RESOLVE_FILE);
    }
  }
}
