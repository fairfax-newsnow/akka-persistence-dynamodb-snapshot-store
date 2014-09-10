To use this dynamodb-snapshot-store plugin, the following environment variables should be defined, e.g. by exporting them

* AWS_ACCESS_KEY_ID
* AWS_SECRET_ACCESS_KEY
* AWS_DYNAMODB_ENDPOINT
* SNAPSHOT_TABLE

The reason is explained in reference.conf.

** AWS_ACCESS_KEY_ID ** and ** AWS_SECRET_ACCESS_KEY ** are also required to publish this artifact to the mvn repository in S3.