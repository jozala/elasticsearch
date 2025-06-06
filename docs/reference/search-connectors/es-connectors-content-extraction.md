---
mapped_pages:
  - https://www.elastic.co/guide/en/elasticsearch/reference/current/es-connectors-content-extraction.html
---

# Content extraction [es-connectors-content-extraction]

Connectors use the [Elastic ingest attachment processor^](/reference/enrich-processor/attachment.md) to extract file contents. The processor extracts files using the [Apache Tika](https://tika.apache.org) text extraction library. The logic for content extraction is defined in [utils.py](https://github.com/elastic/connectors/tree/main/connectors/utils.py).

While intended primarily for PDF and Microsoft Office formats, you can use any of the [supported formats](#es-connectors-content-extraction-supported-file-types).

Search uses an [Elasticsearch ingest pipeline^](docs-content://manage-data/ingest/transform-enrich/ingest-pipelines.md) to power binary content extraction. The default pipeline, `search-default-ingestion` is automatically created.

You can [view^](docs-content://manage-data/ingest/transform-enrich/ingest-pipelines.md#create-manage-ingest-pipelines) this pipeline in Kibana. Customizing your pipeline usage is also an option. See [Ingest pipelines for Search indices](docs-content://solutions/search/ingest-for-search.md).

For advanced use cases, the [self-hosted extraction service](#es-connectors-content-extraction-local) can be used to extract content from files larger than 10MB.


## Supported file types [es-connectors-content-extraction-supported-file-types]

The following file types are supported:

* `.txt`
* `.py`
* `.rst`
* `.html`
* `.markdown`
* `.json`
* `.xml`
* `.csv`
* `.md`
* `.ppt`
* `.rtf`
* `.docx`
* `.odt`
* `.xls`
* `.xlsx`
* `.rb`
* `.paper`
* `.sh`
* `.pptx`
* `.pdf`
* `.doc`

::::{note}
The ingest attachment processor does not support compressed files, e.g., an archive file containing a set of PDFs. Expand the archive file and make individual uncompressed files available for the connector to process.

::::



## Extraction Service [es-connectors-content-extraction-local]

::::{note}
Currently, content extraction from large files via the Extraction Service is available for a subset of our **self-managed connectors**. It is not available for Elastic managed connectors running on Elastic Cloud. This feature is in **beta**.

::::


Standard content extraction is done via the Attachment Processor, through Elasticsearch Ingest Pipelines. The self-managed connector limits file sizes for pipeline extraction to 10MB per file (Elasticsearch also has a hard limit of 100MB per file).

For use cases that require extracting content from files larger than these limits, the **self-managed extraction service** can be used for self-managed connectors. Instead of sending the file as an `attachment` to Elasticsearch, the file’s content is extracted at the edge by the extraction service before ingestion. The extracted text is then included as the `body` field of a document when it is ingested.

To use this feature, you will need to do the following:

* [Run the self-hosted content extraction service](#es-connectors-content-extraction-data-extraction-service)
* [Add the required configuration settings](#es-connectors-extraction-service-configuration)
* Set the value of the configurable field `use_text_extraction_service` to `true`

::::{tip}
The data extraction service code is now available in this public repository: [https://github.com/elastic/data-extraction-service](https://github.com/elastic/data-extraction-service).

::::



### Available connectors [es-connectors-content-extraction-available-connectors]

Local content extraction is available for the following self-managed connectors:

* [Azure Blob Storage](/reference/search-connectors/es-connectors-azure-blob.md)
* [Confluence](/reference/search-connectors/es-connectors-confluence.md)
* [Dropbox](/reference/search-connectors/es-connectors-dropbox.md)
* [GitHub](/reference/search-connectors/es-connectors-github.md)
* [Google Cloud Storage](/reference/search-connectors/es-connectors-google-cloud.md)
* [Google Drive](/reference/search-connectors/es-connectors-google-drive.md)
* [Jira](/reference/search-connectors/es-connectors-jira.md)
* [Network drive](/reference/search-connectors/es-connectors-network-drive.md)
* [OneDrive](/reference/search-connectors/es-connectors-onedrive.md)
* [Outlook](/reference/search-connectors/es-connectors-outlook.md)
* [S3](/reference/search-connectors/es-connectors-s3.md)
* [Salesforce](/reference/search-connectors/es-connectors-salesforce.md)
* [ServiceNow](/reference/search-connectors/es-connectors-servicenow.md)
* [SharePoint Online](/reference/search-connectors/es-connectors-sharepoint-online.md)
* [SharePoint Server](/reference/search-connectors/es-connectors-sharepoint.md)
* [Zoom](/reference/search-connectors/es-connectors-zoom.md)


### Running the extraction service [es-connectors-content-extraction-data-extraction-service]

Self-hosted content extraction is handled by a **separate** extraction service.

The versions for the extraction service do not align with the Elastic stack. For versions after `8.11.x` (including 9.0.0), you should use extraction service version `0.3.x`.

You can run the service with the following command:

```bash
$ docker run \
  -p 8090:8090 \
  -it \
  --name extraction-service \
  docker.elastic.co/integrations/data-extraction-service:$EXTRACTION_SERVICE_VERSION
```


### Configuring the extraction service [es-connectors-extraction-service-configuration]

You can enable your self-managed connector to use the self-hosted extraction service by adding the required configuration. The self-managed connector determines if the extraction service is enabled by the presence of these fields in the configuration file.

1. Open the `config.yml` configuration file in your text editor of choice.
2. Add the following fields. They can be added anywhere in the file, so long as they begin at the root level.

```yaml
# data-extraction-service settings
extraction_service:
  host: http://localhost:8090
```

::::{note}
There is no password protection between the self-managed connector and the extraction service. Self-hosted extraction should only be used if the two services are running on the same network and behind the same firewall.

::::


| Field | Description |
| --- | --- |
| `host` | The endpoint for the extraction service. `http://localhost:8090` can be used if it is running on the same server as your self-managed connector. |

The self-managed connector will perform a preflight check against the configured `host` value at startup. The following line will be output to the log if the data extraction service was found and is running normally.

```bash
Data extraction service found at <HOST>.
```

If you don’t see this log at startup, refer to [troubleshooting self-hosted content extraction service](#es-connectors-content-extraction-troubleshooting).


#### Advanced configuration [es-connectors-content-extraction-advanced-configuration]

The following fields can be included in the configuration file. They are optional and will fallback on default values if they are not specified.

```yaml
# data-extraction-service settings
extraction_service:
  host: http://localhost:8090
  timeout: 30
  use_file_pointers: false
  stream_chunk_size: 65536
  shared_volume_dir: '/app/files'
```

| Advanced Field | Description |
| --- | --- |
| `timeout` | Timeout limit in seconds for content extraction. Defaults to `30` if not set. Increase this if you have very large files that timeout during content extraction. In the event of a timeout, the indexed document’s `body` field will be an empty string. |
| `use_file_pointers` | Whether or not to use file pointers instead of sending files to the extraction service. Defaults to `false`. Refer to [using file pointers](#es-connectors-content-extraction-data-extraction-service-file-pointers) for more details about this setting. |
| `stream_chunk_size` | The size that files are chunked to facilitate streaming to extraction service, in bytes. Defaults to 65536 (64 KB). Only applicable if `use_file_pointers` is `false`. Increasing this value may speed up the connector, but will also increase memory usage. |
| `shared_volume_dir` | The shared volume from which the data extraction service will extract files. Defaults to `/app/files`. Only applicable if `use_file_pointers` is `true`. |


### Using file pointers [es-connectors-content-extraction-data-extraction-service-file-pointers]

The self-hosted extraction service can be set up to use file pointers instead of sending files via HTTP requests. File pointers are faster than sending files and consume less memory, but require the connector framework and the extraction service to be able to share a file system. This can be set up with both a dockerized and non-dockerized self-managed connector.


#### Configuration for non-dockerized self-managed connectors [es-connectors-content-extraction-data-extraction-service-file-pointers-configuration]

If you are running a non-dockerized version of the self-managed connector, you need to determine the local directory where you’ll download files for extraction. This can be anywhere on your file system that you are comfortable using. Be aware that the self-managed connector will download files with randomized filenames to this directory, so there is a chance that any files already present will be overwritten. For that reason, we recommend using a dedicated directory for self-hosted extraction.

$$$es-connectors-content-extraction-data-extraction-service-file-pointers-configuration-example$$$
**Example**

1. For this example, we will be using `/app/files` as both our local directory and our container directory. When you run the extraction service docker container, you can mount the directory as a volume using the command-line option `-v /app/files:/app/files`.

    ```bash
    $ docker run \
      -p 8090:8090 \
      -it \
      -v /app/files:/app/files \
      --name extraction-service \
      docker.elastic.co/integrations/data-extraction-service:$EXTRACTION_SERVICE_VERSION
    ```

    ::::{note}
    Due to how this feature works in the codebase for non-dockerized setups, **the local filepath and the docker container’s filepath must be identical**. For example, if using `/app/files`, you must mount the directory as `-v /app/files:/app/files`. If either directory is different, the self-managed connector will be unable to provide an accurate file pointer for the extraction service. This is not a factor when using a dockerized self-managed connector.

    ::::

2. Next, before running the self-managed connector, be sure to update the config file with the correct information.

    ```yaml
    # data-extraction-service settings
    extraction_service:
      host: http://localhost:8090
      use_file_pointers: true
      shared_volume_dir: '/app/files'
    ```

3. Then all that’s left is to start the self-managed connector and run a sync. If you encounter any unexpected errors, refer to [troubleshooting the self-hosted content extraction service](#es-connectors-content-extraction-troubleshooting).


#### Configuration for dockerized self-managed connectors [es-connectors-content-extraction-data-extraction-service-file-pointers-configuration-dockerized]

When using self-hosted extraction from a dockerized self-managed connector, there are a few extra steps required on top of [running the self-managed connector in docker](https://github.com/elastic/connectors/tree/main/docs/DOCKER.md).

* The self-hosted extraction service will need to share the same network that the self-managed connector and Elasticsearch are sharing.
* The self-managed connector and the extraction service will also need to share a volume. You can decide what directory inside these docker containers the volume will be mounted onto, but the directory must be the same for both docker containers.

$$$es-connectors-content-extraction-data-extraction-service-file-pointers-configuration-dockerized-example$$$
**Example**

1. First, set up a volume for the two docker containers to share. This will be where files are downloaded into and then extracted from.

    ```bash
    $ docker volume create --name extraction-service-volume
    ```

2. If you haven’t set up a network yet, you can create it now.

    ```bash
    $ docker network create elastic
    ```

3. Include the docker volume name and the network as arguments when running the extraction service. For this example, we will be using `/app/files` as our container directory.

    ```bash
    $ docker run \
      -p 8090:8090 \
      -it \
      -v extraction-service-volume:/app/files \
      --network "elastic" \
      --name extraction-service \
      docker.elastic.co/integrations/data-extraction-service:$EXTRACTION_SERVICE_VERSION
    ```

4. Next, you can follow the instructions for [running the self-managed connector in docker](https://github.com/elastic/connectors/tree/main/docs/DOCKER.md) until step `4. Update the configuration file for your self-managed connector`. When setting up your configuration, be sure to add the following settings for the self-hosted content extraction service. Note that the `host` will now refer to an internal docker endpoint instead of localhost.

    ```yaml
    # data-extraction-service settings
    extraction_service:
      host: http://host.docker.internal:8090
      use_file_pointers: true
      shared_volume_dir: '/app/files'
    ```

5. Next, during step `5. Run the Docker image`, we only need to add our new shared volume in the run command using `-v extraction-service-volume:/app/files`.

    ```bash
    $ docker run \
      -v ~/connectors-config:/config \
      -v extraction-service-volume:/app/files \
      --network "elastic" \
      --tty \
      --rm \
      docker.elastic.co/integrations/elastic-connectors:$CONNECTOR_CLIENT_VERSION \
      /app/bin/elastic-ingest \
      -c /config/config.yml
    ```

6. Now the self-managed connector and extraction service docker containers should be set up to share files. Run a test sync to make sure everything is configured correctly. If you encounter any unexpected errors, refer to [troubleshooting the self-hosted extraction service](#es-connectors-content-extraction-troubleshooting).


### Self-hosted extraction service logs [es-connectors-content-extraction-local-logs]

The extraction service produces two different log files that may be informative when troubleshooting. These are saved at the following file locations internally in the docker container:

* `/var/log/openresty.log` for request traffic logs
* `/var/log/tika.log` for tikaserver jar logs

Logs can be viewed from outside of docker by combining `docker exec` with the `tail` command.

```bash
$ docker exec extraction-service /bin/sh -c "tail /var/log/openresty.log"
$ docker exec extraction-service /bin/sh -c "tail /var/log/tika.log"
```


### Troubleshooting the self-hosted extraction service [es-connectors-content-extraction-troubleshooting]

The following warning logs may appear while using self-hosted extraction service. Each log in this section is followed by a description of what may have happened, and suggested fixes.

```bash
Extraction service is not configured, skipping its preflight check.
```

The configuration file is missing the `extraction_service.host` field. If you want to use this service, check that the configuration is formatted correctly and that the required field is present.

```bash
Data extraction service found at <HOST>, but health-check returned <RESPONSE STATUS>.
```

The `/ping` endpoint returned a non-`200` response. This could mean that the extraction service is unhealthy and may need to be restarted, or that the configured `extraction_service.host` is incorrect. You can find more information about what happened in the [data extraction service logs](#es-connectors-content-extraction-local-logs).

```bash
Expected to find a running instance of data extraction service at <HOST> but failed. <ERROR>.
```

The health check returned either a timeout or client connection error.

* A timeout may be caused by the extraction service server not running, or not being accessible from the configured `host` in the configuration file.
* A server connection error is an internal error on the extraction service. You will need to investigate the [data extraction service logs](#es-connectors-content-extraction-local-logs).

```bash
Extraction service has been initialised but no extraction service configuration was found. No text will be extracted for this sync.
```

You have enabled self-hosted extraction service for the connector, but the configuration file is missing the `extraction_service.host` field. Check that the configuration is formatted correctly and that the required field is present.

```bash
Extraction service could not parse <FILENAME>. Status: <RESPONSE STATUS>; <ERROR NAME>: <ERROR MESSAGE>.
```

This warning will appear every time a file is not extractable. Generally the `<ERROR MESSAGE>` will provide an explanation for why extraction failed. Contact support if the message is unclear. When a file fails extraction, it will be indexed with an empty string in the `body` field.
