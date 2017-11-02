/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListMultipartUploadsRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.MultipartUploadListing;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

import static org.apache.hadoop.fs.s3a.Constants.*;

/**
 * A wrapper around {@link com.amazonaws.services.s3.AmazonS3} that injects
 * inconsistency and/or errors.  Used for testing S3Guard.
 * Currently only delays listing visibility, not affecting GET.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class InconsistentAmazonS3Client extends AmazonS3Client {

  /**
   * Keys containing this substring will be subject to delayed visibility.
   */
  public static final String DEFAULT_DELAY_KEY_SUBSTRING = "DELAY_LISTING_ME";

  /**
   * How many seconds affected keys will be delayed from appearing in listing.
   * This should probably be a config value.
   */
  public static final long DEFAULT_DELAY_KEY_MSEC = 5 * 1000;

  public static final float DEFAULT_DELAY_KEY_PROBABILITY = 1.0f;

  /** Special config value since we can't store empty strings in XML. */
  public static final String MATCH_ALL_KEYS = "*";

  private static final Logger LOG =
      LoggerFactory.getLogger(InconsistentAmazonS3Client.class);

  /** Empty string matches all keys. */
  private String delayKeySubstring;

  /** Probability to delay visibility of a matching key. */
  private float delayKeyProbability;

  /** Time in milliseconds to delay visibility of newly modified object. */
  private long delayKeyMsec;

  /**
   * Probability of throttling a request.
   */
  private float throttleProbability;

  /**
   * Counter of failures since last reset.
   */
  private final AtomicLong failureCounter = new AtomicLong(0);

  /**
   * limit for failures before operations succeed; if 0 then "no limit".
   */
  private int failureLimit = 0;

  /**
   * Composite of data we need to track about recently deleted objects:
   * when it was deleted (same was with recently put objects) and the object
   * summary (since we should keep returning it for sometime after its
   * deletion).
   */
  private static class Delete {
    private Long time;
    private S3ObjectSummary summary;

    Delete(Long time, S3ObjectSummary summary) {
      this.time = time;
      this.summary = summary;
    }

    public Long time() {
      return time;
    }

    public S3ObjectSummary summary() {
      return summary;
    }
  }

  /**
   * Map of key to delay -> time it was deleted + object summary (object summary
   * is null for prefixes.
   */
  private Map<String, Delete> delayedDeletes = new HashMap<>();

  /** Map of key to delay -> time it was created. */
  private Map<String, Long> delayedPutKeys = new HashMap<>();

  public InconsistentAmazonS3Client(AWSCredentialsProvider credentials,
      ClientConfiguration clientConfiguration, Configuration conf) {
    super(credentials, clientConfiguration);
    setupConfig(conf);
  }

  protected void setupConfig(Configuration conf) {

    delayKeySubstring = conf.get(FAIL_INJECT_INCONSISTENCY_KEY,
        DEFAULT_DELAY_KEY_SUBSTRING);
    // "" is a substring of all strings, use it to match all keys.
    if (delayKeySubstring.equals(MATCH_ALL_KEYS)) {
      delayKeySubstring = "";
    }
    delayKeyProbability = validProbability(
        conf.getFloat(FAIL_INJECT_INCONSISTENCY_PROBABILITY,
            DEFAULT_DELAY_KEY_PROBABILITY));
    delayKeyMsec = conf.getLong(FAIL_INJECT_INCONSISTENCY_MSEC,
        DEFAULT_DELAY_KEY_MSEC);
    setThrottleProbability(conf.getFloat(FAIL_INJECT_THROTTLE_PROBABILITY,
        0.0f));
    LOG.info("{}", this);
  }

  @Override
  public String toString() {
    return String.format(
        "Inconsistent S3 Client with"
            + " %s msec delay, substring %s, delay probability %s;"
            + " throttle probability %s"
            + "; failure limit %d, failure count %d",
        delayKeyMsec, delayKeySubstring, delayKeyProbability,
        throttleProbability, failureLimit, failureCounter.get());
  }

  /**
   * Clear all oustanding inconsistent keys.  After calling this function,
   * listings should behave normally (no failure injection), until additional
   * keys are matched for delay, e.g. via putObject(), deleteObject().
   */
  public void clearInconsistency() {
    LOG.info("clearing all delayed puts / deletes");
    delayedDeletes.clear();
    delayedPutKeys.clear();
  }

  /**
   * Convenience function for test code to cast from supertype.
   * @param c supertype to cast from
   * @return subtype, not null
   * @throws Exception on error
   */
  public static InconsistentAmazonS3Client castFrom(AmazonS3 c) throws
      Exception {
    InconsistentAmazonS3Client ic = null;
    if (c instanceof InconsistentAmazonS3Client) {
      ic = (InconsistentAmazonS3Client) c;
    }
    Preconditions.checkNotNull(ic, "Not an instance of " +
        "InconsistentAmazonS3Client");
    return ic;
  }

  @Override
  public DeleteObjectsResult deleteObjects(DeleteObjectsRequest
      deleteObjectsRequest)
      throws AmazonClientException, AmazonServiceException {
    maybeFail();
    for (DeleteObjectsRequest.KeyVersion keyVersion :
        deleteObjectsRequest.getKeys()) {
      registerDeleteObject(keyVersion.getKey(), deleteObjectsRequest.getBucketName());
    }
    return super.deleteObjects(deleteObjectsRequest);
  }

  @Override
  public void deleteObject(DeleteObjectRequest deleteObjectRequest)
      throws AmazonClientException, AmazonServiceException {
    String key = deleteObjectRequest.getKey();
    LOG.debug("key {}", key);
    maybeFail();
    registerDeleteObject(key, deleteObjectRequest.getBucketName());
    super.deleteObject(deleteObjectRequest);
  }

  /* We should only need to override this version of putObject() */
  @Override
  public PutObjectResult putObject(PutObjectRequest putObjectRequest)
      throws AmazonClientException, AmazonServiceException {
    LOG.debug("key {}", putObjectRequest.getKey());
    maybeFail();
    registerPutObject(putObjectRequest);
    return super.putObject(putObjectRequest);
  }

  /* We should only need to override these versions of listObjects() */
  @Override
  public ObjectListing listObjects(ListObjectsRequest listObjectsRequest)
      throws AmazonClientException, AmazonServiceException {
    maybeFail();
    return innerlistObjects(listObjectsRequest);
  }

  /**
   * Run the list object call without any failure probability.
   * This stops a very aggressive failure rate from completely overloading
   * the retry logic.
   * @param listObjectsRequest request
   * @return listing
   * @throws AmazonClientException failure
   */
  private ObjectListing innerlistObjects(ListObjectsRequest listObjectsRequest)
      throws AmazonClientException, AmazonServiceException {
    LOG.debug("prefix {}", listObjectsRequest.getPrefix());
    ObjectListing listing = super.listObjects(listObjectsRequest);
    listing = filterListObjects(listing);
    listing = restoreListObjects(listObjectsRequest, listing);
    return listing;
  }

  /* We should only need to override these versions of listObjects() */
  @Override
  public ListObjectsV2Result listObjectsV2(ListObjectsV2Request request)
      throws AmazonClientException, AmazonServiceException {
    maybeFail();
    return innerListObjectsV2(request);
  }

  /**
   * Non failing V2 list object request.
   * @param request request
   * @return result.
   */
  private ListObjectsV2Result innerListObjectsV2(ListObjectsV2Request request) {
    LOG.debug("prefix {}", request.getPrefix());
    ListObjectsV2Result listing = super.listObjectsV2(request);
    listing = filterListObjectsV2(listing);
    listing = restoreListObjectsV2(request, listing);
    return listing;
  }

  private void addSummaryIfNotPresent(List<S3ObjectSummary> list,
      S3ObjectSummary item) {
    // Behavior of S3ObjectSummary
    String key = item.getKey();
    if (list.stream().noneMatch((member) -> member.getKey().equals(key))) {
      list.add(item);
    }
  }

  /**
   * Add prefix of child to given list.  The added prefix will be equal to
   * ancestor plus one directory past ancestor.  e.g.:
   * if ancestor is "/a/b/c" and child is "/a/b/c/d/e/file" then "a/b/c/d" is
   * added to list.
   * @param prefixes list to add to
   * @param ancestor path we are listing in
   * @param child full path to get prefix from
   */
  private void addPrefixIfNotPresent(List<String> prefixes, String ancestor,
      String child) {
    Path prefixCandidate = new Path(child).getParent();
    Path ancestorPath = new Path(ancestor);
    Preconditions.checkArgument(child.startsWith(ancestor), "%s does not " +
        "start with %s", child, ancestor);
    while (!prefixCandidate.isRoot()) {
      Path nextParent = prefixCandidate.getParent();
      if (nextParent.equals(ancestorPath)) {
        String prefix = prefixCandidate.toString();
        if (!prefixes.contains(prefix)) {
          prefixes.add(prefix);
        }
        return;
      }
      prefixCandidate = nextParent;
    }
  }

  /**
   * Checks that the parent key is an ancestor of the child key.
   * @param parent key that may be the parent.
   * @param child key that may be the child.
   * @param recursive if false, only return true for direct children.  If
   *                  true, any descendant will count.
   * @return true if parent is an ancestor of child
   */
  private boolean isDescendant(String parent, String child, boolean recursive) {
    if (recursive) {
      if (!parent.endsWith("/")) {
        parent = parent + "/";
      }
      return child.startsWith(parent);
    } else {
      Path actualParentPath = new Path(child).getParent();
      Path expectedParentPath = new Path(parent);
      return actualParentPath.equals(expectedParentPath);
    }
  }

  /**
   * Simulate eventual consistency of delete for this list operation:  Any
   * recently-deleted keys will be added.
   * @param request List request
   * @param rawListing listing returned from underlying S3
   * @return listing with recently-deleted items restored
   */
  private ObjectListing restoreListObjects(ListObjectsRequest request,
      ObjectListing rawListing) {
    List<S3ObjectSummary> outputList = rawListing.getObjectSummaries();
    List<String> outputPrefixes = rawListing.getCommonPrefixes();
    // recursive list has no delimiter, returns everything that matches a
    // prefix.
    boolean recursiveObjectList = !("/".equals(request.getDelimiter()));
    String prefix = request.getPrefix();

    restoreDeleted(outputList, outputPrefixes, recursiveObjectList, prefix);
    return new CustomObjectListing(rawListing, outputList, outputPrefixes);
  }

  /**
   * V2 list API variant of
   * {@link #restoreListObjects(ListObjectsRequest, ObjectListing)}.
   * @param request original v2 list request
   * @param result raw s3 result
   */
  private ListObjectsV2Result restoreListObjectsV2(ListObjectsV2Request request,
      ListObjectsV2Result result) {
    List<S3ObjectSummary> outputList = result.getObjectSummaries();
    List<String> outputPrefixes = result.getCommonPrefixes();
    // recursive list has no delimiter, returns everything that matches a
    // prefix.
    boolean recursiveObjectList = !("/".equals(request.getDelimiter()));
    String prefix = request.getPrefix();

    restoreDeleted(outputList, outputPrefixes, recursiveObjectList, prefix);
    return new CustomListObjectsV2Result(result, outputList, outputPrefixes);
  }


  /**
   * Main logic for
   * {@link #restoreListObjects(ListObjectsRequest, ObjectListing)} and
   * the v2 variant above.
   * @param summaries object summary list to modify.
   * @param prefixes prefix list to modify
   * @param recursive true if recursive list request
   * @param prefix prefix for original list request
   */
  private void restoreDeleted(List<S3ObjectSummary> summaries,
      List<String> prefixes, boolean recursive, String prefix) {

    // Go through all deleted keys
    for (String key : new HashSet<>(delayedDeletes.keySet())) {
      Delete delete = delayedDeletes.get(key);
      if (isKeyDelayed(delete.time(), key)) {
        if (isDescendant(prefix, key, recursive)) {
          if (delete.summary() != null) {
            addSummaryIfNotPresent(summaries, delete.summary());
          }
        }
        // Non-recursive list has delimiter: will return rolled-up prefixes for
        // all keys that are not direct children
        if (!recursive) {
          if (isDescendant(prefix, key, true)) {
            addPrefixIfNotPresent(prefixes, prefix, key);
          }
        }
      } else {
        // Clean up any expired entries
        delayedDeletes.remove(key);
      }
    }
  }

  private ObjectListing filterListObjects(ObjectListing rawListing) {

    // Filter object listing
    List<S3ObjectSummary> outputList = filterSummaries(
        rawListing.getObjectSummaries());

    // Filter prefixes (directories)
    List<String> outputPrefixes = filterPrefixes(
        rawListing.getCommonPrefixes());

    return new CustomObjectListing(rawListing, outputList, outputPrefixes);
  }

  private ListObjectsV2Result filterListObjectsV2(ListObjectsV2Result raw) {
    // Filter object listing
    List<S3ObjectSummary> outputList = filterSummaries(
        raw.getObjectSummaries());

    // Filter prefixes (directories)
    List<String> outputPrefixes = filterPrefixes(raw.getCommonPrefixes());

    return new CustomListObjectsV2Result(raw, outputList, outputPrefixes);
  }

  private List<S3ObjectSummary> filterSummaries(
      List<S3ObjectSummary> summaries) {
    List<S3ObjectSummary> outputList = new ArrayList<>();
    for (S3ObjectSummary s : summaries) {
      String key = s.getKey();
      if (!isKeyDelayed(delayedPutKeys.get(key), key)) {
        outputList.add(s);
      }
    }
    return outputList;
  }

  private List<String> filterPrefixes(List<String> prefixes) {
    return prefixes.stream()
        .filter(key -> !isKeyDelayed(delayedPutKeys.get(key), key))
        .collect(Collectors.toList());
  }

  private boolean isKeyDelayed(Long enqueueTime, String key) {
    if (enqueueTime == null) {
      LOG.debug("no delay for key {}", key);
      return false;
    }
    long currentTime = System.currentTimeMillis();
    long deadline = enqueueTime + delayKeyMsec;
    if (currentTime >= deadline) {
      delayedDeletes.remove(key);
      LOG.debug("no longer delaying {}", key);
      return false;
    } else {
      LOG.info("delaying {}", key);
      return true;
    }
  }

  private void registerDeleteObject(String key, String bucket) {
    if (shouldDelay(key)) {
      // Record summary so we can add it back for some time post-deletion
      ListObjectsRequest request = new ListObjectsRequest()
              .withBucketName(bucket)
              .withPrefix(key);
      S3ObjectSummary summary = innerlistObjects(request).getObjectSummaries()
          .stream()
          .filter(result -> result.getKey().equals(key))
          .findFirst()
          .orElse(null);
      delayedDeletes.put(key, new Delete(System.currentTimeMillis(), summary));
    }
  }

  private void registerPutObject(PutObjectRequest req) {
    String key = req.getKey();
    if (shouldDelay(key)) {
      enqueueDelayedPut(key);
    }
  }

  /**
   * Should we delay listing visibility for this key?
   * @param key key which is being put
   * @return true if we should delay
   */
  private boolean shouldDelay(String key) {
    boolean delay = key.contains(delayKeySubstring);
    delay = delay && trueWithProbability(delayKeyProbability);
    LOG.debug("{} -> {}", key, delay);
    return delay;
  }


  private boolean trueWithProbability(float p) {
    return Math.random() < p;
  }

  /**
   * Record this key as something that should not become visible in
   * listObject replies for a while, to simulate eventual list consistency.
   * @param key key to delay visibility of
   */
  private void enqueueDelayedPut(String key) {
    LOG.debug("delaying put of {}", key);
    delayedPutKeys.put(key, System.currentTimeMillis());
  }

  @Override
  public CompleteMultipartUploadResult completeMultipartUpload(
      CompleteMultipartUploadRequest completeMultipartUploadRequest)
      throws SdkClientException, AmazonServiceException {
    maybeFail();
    return super.completeMultipartUpload(completeMultipartUploadRequest);
  }

  @Override
  public UploadPartResult uploadPart(UploadPartRequest uploadPartRequest)
      throws SdkClientException, AmazonServiceException {
    maybeFail();
    return super.uploadPart(uploadPartRequest);
  }

  @Override
  public InitiateMultipartUploadResult initiateMultipartUpload(
      InitiateMultipartUploadRequest initiateMultipartUploadRequest)
      throws SdkClientException, AmazonServiceException {
    maybeFail();
    return super.initiateMultipartUpload(initiateMultipartUploadRequest);
  }

  @Override
  public MultipartUploadListing listMultipartUploads(
      ListMultipartUploadsRequest listMultipartUploadsRequest)
      throws SdkClientException, AmazonServiceException {
    maybeFail();
    return super.listMultipartUploads(listMultipartUploadsRequest);
  }

  public float getDelayKeyProbability() {
    return delayKeyProbability;
  }

  public long getDelayKeyMsec() {
    return delayKeyMsec;
  }

  /**
   * Get the probability of the request being throttled.
   * @return a value 0 - 1.0f.
   */
  public float getThrottleProbability() {
    return throttleProbability;
  }

  /**
   * Set the probability of throttling a request.
   * @param throttleProbability the probability of a request being throttled.
   */
  public void setThrottleProbability(float throttleProbability) {
    this.throttleProbability = validProbability(throttleProbability);
  }

  /**
   * Validate a probability option.
   * @param p probability
   * @return the probability, if valid
   * @throws IllegalArgumentException if the probability is out of range.
   */
  private float validProbability(float p) {
    Preconditions.checkArgument(p >= 0.0f && p <= 1.0f,
        "Probability out of range 0 to 1 %s", p);
    return p;
  }

  /**
   * Conditionally fail the operation.
   * @throws AmazonClientException if the client chooses to fail
   * the request.
   */
  private void maybeFail() throws AmazonClientException {
    // code structure here is to line up for more failures later
    AmazonServiceException ex = null;
    if (trueWithProbability(throttleProbability)) {
      // throttle the request
      ex = new AmazonServiceException("throttled"
          + " count = " + (failureCounter.get() + 1), null);
      ex.setStatusCode(503);
    }

    if (ex != null) {
      long count = failureCounter.incrementAndGet();
      if (failureLimit == 0
          || (failureLimit > 0 && count < failureLimit)) {
        throw ex;
      }
    }
  }

  /**
   * Set the limit on failures before all operations pass through.
   * This resets the failure count.
   * @param limit limit; "0" means "no limit"
   */
  public void setFailureLimit(int limit) {
    this.failureLimit = limit;
    failureCounter.set(0);
  }

  /** Since ObjectListing is immutable, we just override it with wrapper. */
  @SuppressWarnings("serial")
  private static class CustomObjectListing extends ObjectListing {

    private final List<S3ObjectSummary> customListing;
    private final List<String> customPrefixes;

    CustomObjectListing(ObjectListing rawListing,
        List<S3ObjectSummary> customListing,
        List<String> customPrefixes) {
      super();
      this.customListing = customListing;
      this.customPrefixes = customPrefixes;

      this.setBucketName(rawListing.getBucketName());
      this.setCommonPrefixes(rawListing.getCommonPrefixes());
      this.setDelimiter(rawListing.getDelimiter());
      this.setEncodingType(rawListing.getEncodingType());
      this.setMarker(rawListing.getMarker());
      this.setMaxKeys(rawListing.getMaxKeys());
      this.setNextMarker(rawListing.getNextMarker());
      this.setPrefix(rawListing.getPrefix());
      this.setTruncated(rawListing.isTruncated());
    }

    @Override
    public List<S3ObjectSummary> getObjectSummaries() {
      return customListing;
    }

    @Override
    public List<String> getCommonPrefixes() {
      return customPrefixes;
    }
  }

  @SuppressWarnings("serial")
  private static class CustomListObjectsV2Result extends ListObjectsV2Result {

    private final List<S3ObjectSummary> customListing;
    private final List<String> customPrefixes;

    CustomListObjectsV2Result(ListObjectsV2Result raw,
        List<S3ObjectSummary> customListing, List<String> customPrefixes) {
      super();
      this.customListing = customListing;
      this.customPrefixes = customPrefixes;

      this.setBucketName(raw.getBucketName());
      this.setCommonPrefixes(raw.getCommonPrefixes());
      this.setDelimiter(raw.getDelimiter());
      this.setEncodingType(raw.getEncodingType());
      this.setStartAfter(raw.getStartAfter());
      this.setMaxKeys(raw.getMaxKeys());
      this.setContinuationToken(raw.getContinuationToken());
      this.setPrefix(raw.getPrefix());
      this.setTruncated(raw.isTruncated());
    }

    @Override
    public List<S3ObjectSummary> getObjectSummaries() {
      return customListing;
    }

    @Override
    public List<String> getCommonPrefixes() {
      return customPrefixes;
    }
  }
}
