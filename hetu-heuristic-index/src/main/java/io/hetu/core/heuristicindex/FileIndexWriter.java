/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hetu.core.heuristicindex;

import io.airlift.log.Logger;
import io.hetu.core.filesystem.HetuLocalFileSystemClient;
import io.hetu.core.filesystem.LocalConfig;
import io.hetu.core.heuristicindex.util.IndexConstants;
import io.hetu.core.heuristicindex.util.IndexServiceUtils;
import io.prestosql.spi.HetuConstant;
import io.prestosql.spi.connector.CreateIndexMetadata;
import io.prestosql.spi.filesystem.HetuFileSystemClient;
import io.prestosql.spi.heuristicindex.Index;
import io.prestosql.spi.heuristicindex.IndexWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.prestosql.spi.HetuConstant.DATASOURCE_PAGE_COUNT;
import static io.prestosql.spi.HetuConstant.DATASOURCE_STRIPE_OFFSET;
import static java.util.Objects.requireNonNull;

/**
 * All of the values added to this index writer should belong to one single data source file (e.g. one ORC file on Hive)
 * <p>
 * This writer cache the result in an internal map in (offset -> index) format and write them to disk whenever finish() is called
 *
 * @since 2019-10-11
 */
public class FileIndexWriter
        implements IndexWriter
{
    private static final HetuFileSystemClient LOCAL_FS_CLIENT = new HetuLocalFileSystemClient(new LocalConfig(new Properties()), Paths.get("/"));
    private static final Logger LOG = Logger.get(FileIndexWriter.class);

    private final String dataSourceFileName;
    private final String dataSourceFileLastModifiedTime;
    private final Map<Integer, Map<String, List<Object>>> indexValues;
    private final Map<Integer, AtomicInteger> pageCountExpected;
    private final CreateIndexMetadata createIndexMetadata;
    private final HetuFileSystemClient fs;
    private final Path root;
    private Path tmpPath;

    /**
     * Constructor
     *
     * @param createIndexMetadata metadata of create index, includes indexName, tableName, indexType, indexColumns and partitions
     * @param fs filesystem client to access filesystem where the indexes are persisted/stored
     */
    public FileIndexWriter(CreateIndexMetadata createIndexMetadata, Properties connectorMetadata, HetuFileSystemClient fs, Path root)
    {
        this.createIndexMetadata = createIndexMetadata;
        this.dataSourceFileName = URI.create(connectorMetadata.getProperty(HetuConstant.DATASOURCE_FILE_PATH)).getPath();
        this.dataSourceFileLastModifiedTime = connectorMetadata.getProperty(HetuConstant.DATASOURCE_FILE_MODIFICATION);
        this.fs = requireNonNull(fs);
        this.root = root;
        this.indexValues = new ConcurrentHashMap<>();
        this.pageCountExpected = new ConcurrentHashMap<>();
    }

    /**
     * This method IS thread-safe. Multiple operators can add data to one writer in parallel.
     *
     * @param values values to be indexed
     * @param connectorMetadata metadata for the index
     */
    @Override
    public void addData(Map<String, List<Object>> values, Properties connectorMetadata)
            throws IOException
    {
        int stripeOffset = Integer.parseInt(connectorMetadata.getProperty(DATASOURCE_STRIPE_OFFSET));

        // Add values first
        indexValues.computeIfAbsent(stripeOffset, k -> new ConcurrentHashMap<>());
        for (Map.Entry<String, List<Object>> e : values.entrySet()) {
            indexValues.get(stripeOffset).computeIfAbsent(e.getKey(),
                    k -> Collections.synchronizedList(new LinkedList<>())).addAll(e.getValue());
        }

        // Update page count
        int current = pageCountExpected.computeIfAbsent(stripeOffset, k -> new AtomicInteger()).decrementAndGet();
        if (connectorMetadata.getProperty(DATASOURCE_PAGE_COUNT) != null) {
            int expected = Integer.parseInt(connectorMetadata.getProperty(DATASOURCE_PAGE_COUNT));
            int updatedCurrent = pageCountExpected.get(stripeOffset).addAndGet(expected);
            LOG.debug("offset %d finishing page received, expected page count: %d, actual received: %d, remaining: %d",
                    stripeOffset, expected, -current, updatedCurrent);
        }

        // Check page count to know if all pages have been received for a stripe. Persist and delete values if true to save memory
        if (pageCountExpected.get(stripeOffset).get() == 0) {
            synchronized (pageCountExpected.get(stripeOffset)) {
                if (indexValues.containsKey(stripeOffset)) {
                    LOG.debug("All pages for offset %d have been received. Persisting.", stripeOffset);
                    persistStripe(stripeOffset, indexValues.get(stripeOffset));
                    indexValues.remove(stripeOffset);
                }
                else {
                    LOG.debug("All pages for offset %d have been received, but the values are missing. " +
                            "This stripe should have already been persisted by another thread.", stripeOffset);
                }
            }
        }
    }

    /**
     * This method is NOT thread-safe. Should never be called in parallel.
     * <p>
     * Persist index files with following file structure:
     *
     * <pre>
     * /--- {this.root}
     *   |--- INDEX_RECORDS
     *   |--- table1
     *     |--- column
     *       |--- indexType1
     *         |--- [ORC FilePath] (e.g. /user/hive/warehouse/.../OrcFileName/)
     *           ...
     *             |--- [tarFile] (e.g. lastModified=123456.tar)
     *       |--- indexType2
     *   |--- table2
     *   |--- ...
     * </pre>
     *
     * @throws IOException when exceptions occur during persisting
     */
    @Override
    public void persist()
            throws IOException
    {
        for (Integer offset : indexValues.keySet()) {
            LOG.error("Offset %d data is NOT PERSISTED. Current page count: %d. Check debug log.", offset, pageCountExpected.get(offset).get());
        }
        // Package index files for one File and write to remote filesystem
        String table = createIndexMetadata.getTableName();
        String column = createIndexMetadata.getIndexColumns().iterator().next().getKey(); // Support indexing on only one column for now
        String type = createIndexMetadata.getIndexType();
        String lastModifiedFileName = IndexConstants.LAST_MODIFIED_FILE_PREFIX + dataSourceFileLastModifiedTime + ".tar";

        Path tarPath = Paths.get(root.toString(), table, column, type, dataSourceFileName, lastModifiedFileName);

        try {
            IndexServiceUtils.writeToHdfs(LOCAL_FS_CLIENT, fs, tmpPath, tarPath);
        }
        catch (IOException e) {
            LOG.debug("Error copying index files to remote filesystem: ", e);
            // roll back creation
            fs.delete(tarPath);
        }
        finally {
            LOCAL_FS_CLIENT.deleteRecursively(tmpPath);
        }
    }

    private void persistStripe(Integer offset, Map<String, List<Object>> stripeData)
            throws IOException
    {
        synchronized (this) {
            if (tmpPath == null) {
                tmpPath = Files.createTempDirectory("tmp-indexwriter-");
            }
        }

        // Get sum of expected entries
        int expectedNumEntries = 0;
        for (List<Object> l : stripeData.values()) {
            expectedNumEntries += l.size();
        }

        // Create index and put values
        Index index = HeuristicIndexFactory.createIndex(createIndexMetadata.getIndexType().toLowerCase(Locale.ENGLISH));
        index.setProperties(createIndexMetadata.getProperties());
        index.setExpectedNumOfEntries(expectedNumEntries);
        index.addValues(stripeData);

        // Persist one index (e.g. 3.bloom)
        String indexFileName = offset + "." + index.getId().toLowerCase(Locale.ENGLISH);
        try (OutputStream os = LOCAL_FS_CLIENT.newOutputStream(tmpPath.resolve(indexFileName))) {
            index.serialize(os);
        }
    }
}
