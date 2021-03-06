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

import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.hetu.core.filesystem.HetuLocalFileSystemClient;
import io.hetu.core.filesystem.LocalConfig;
import io.hetu.core.heuristicindex.util.IndexConstants;
import io.prestosql.spi.connector.CreateIndexMetadata;
import io.prestosql.spi.filesystem.FileBasedLock;
import io.prestosql.spi.filesystem.HetuFileSystemClient;
import io.prestosql.spi.heuristicindex.Index;
import io.prestosql.spi.heuristicindex.IndexClient;
import io.prestosql.spi.heuristicindex.IndexMetadata;
import io.prestosql.spi.heuristicindex.IndexRecord;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.input.CloseShieldInputStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;

import static io.prestosql.spi.heuristicindex.IndexRecord.COLUMN_DELIMITER;
import static java.util.Objects.requireNonNull;

/**
 * Class for reading and deleting the indices
 *
 * @since 2019-10-15
 */
public class HeuristicIndexClient
        implements IndexClient
{
    private static final HetuFileSystemClient LOCAL_FS_CLIENT = new HetuLocalFileSystemClient(
            new LocalConfig(new Properties()), Paths.get("/"));
    private static final Logger LOG = Logger.get(HeuristicIndexClient.class);

    private HetuFileSystemClient fs;
    private Path root;
    private IndexRecordManager indexRecordManager;

    public HeuristicIndexClient(HetuFileSystemClient fs, Path root)
    {
        this.fs = fs;
        this.root = root;
        this.indexRecordManager = new IndexRecordManager(fs, root);
    }

    @Override
    public List<IndexMetadata> readSplitIndex(String path)
            throws IOException
    {
        requireNonNull(path, "no path specified");

        List<IndexMetadata> indexes = new LinkedList<>();

        Path indexKeyPath = Paths.get(path);
        try {
            if (indexRecordManager.lookUpIndexRecord(indexKeyPath.subpath(0, 1).toString(),
                    new String[] {indexKeyPath.subpath(1, 2).toString()}, indexKeyPath.subpath(2, 3).toString()) == null) {
                // Use index record file to pre-screen. If record does not contain the index, skip loading
                return null;
            }
        }
        catch (Exception e) {
            // On exception, log and continue reading from disk
            LOG.debug("Error reading index records: " + path);
        }
        for (Map.Entry<String, Index> entry : readIndexMap(path).entrySet()) {
            String absolutePath = entry.getKey();
            Path remainder = Paths.get(absolutePath.replaceFirst(root.toString(), ""));
            Path table = remainder.subpath(0, 1);
            remainder = Paths.get(remainder.toString().replaceFirst(table.toString(), ""));
            Path column = remainder.subpath(0, 1);
            remainder = Paths.get(remainder.toString().replaceFirst(column.toString(), ""));
            Path indexType = remainder.subpath(0, 1);
            remainder = Paths.get(remainder.toString().replaceFirst(indexType.toString(), ""));

            Path filenamePath = remainder.getFileName();
            if (filenamePath == null) {
                throw new IllegalArgumentException("Split path cannot be resolved: " + path);
            }
            remainder = remainder.getParent();
            table = table.getFileName();
            column = column.getFileName();
            indexType = indexType.getFileName();
            if (remainder == null || table == null || column == null || indexType == null) {
                throw new IllegalArgumentException("Split path cannot be resolved: " + path);
            }

            String filename = filenamePath.toString();
            long splitStart = Long.parseLong(filename.substring(0, filename.lastIndexOf('.')));
            String timeDir = Paths.get(table.toString(), column.toString(), indexType.toString(), remainder.toString()).toString();
            long lastUpdated = getLastModified(timeDir);

            IndexMetadata index = new IndexMetadata(
                    entry.getValue(),
                    table.toString(),
                    new String[] {column.toString()},
                    root.toString(),
                    remainder.toString(),
                    splitStart,
                    lastUpdated);

            indexes.add(index);
        }

        return indexes;
    }

    @Override
    public long getLastModified(String path)
            throws IOException
    {
        // get the absolute path to the file being read
        Path absolutePath = Paths.get(root.toString(), path);

        try (Stream<Path> children = fs.list(absolutePath)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                Path filenamePath = child.getFileName();
                if (filenamePath != null) {
                    String filename = filenamePath.toString();
                    if (filename.startsWith(IndexConstants.LAST_MODIFIED_FILE_PREFIX)) {
                        String timeStr = filename.replaceAll("\\D", "");
                        return Long.parseLong(timeStr);
                    }
                }
                else {
                    LOG.debug("File path not valid: %s", child);
                    return 0;
                }
            }
        }

        return 0;
    }

    @Override
    public boolean indexRecordExists(CreateIndexMetadata createIndexMetadata)
            throws IOException
    {
        IndexRecord sameNameRecord = indexRecordManager.lookUpIndexRecord(createIndexMetadata.getIndexName());
        IndexRecord sameIndexRecord = indexRecordManager.lookUpIndexRecord(createIndexMetadata.getTableName(),
                createIndexMetadata.getIndexColumns().stream().map(Map.Entry::getKey).toArray(String[]::new),
                createIndexMetadata.getIndexType());

        if (sameNameRecord == null) {
            if (sameIndexRecord != null) {
                LOG.error("Index with same (table,column,indexType) already exists with name [%s]%n%n", sameIndexRecord.name);
                return true;
            }
        }
        else {
            if (sameIndexRecord != null) {
                boolean partitionMerge = createIndexMetadata.getPartitions().size() != 0;
                String conflict = String.join(",", sameIndexRecord.partitions);

                for (String partition : createIndexMetadata.getPartitions()) {
                    if (sameIndexRecord.partitions.isEmpty() || sameIndexRecord.partitions.contains(partition)) {
                        partitionMerge = false;
                        conflict = partition;
                        break;
                    }
                }

                if (!partitionMerge) {
                    LOG.error("Same entry already exists and partitions contain conflicts: [%s]. To update, please delete old index first.%n%n", conflict);
                    return true;
                }
            }
            else {
                LOG.error("Index with name [%s] already exists with different content: [%s]%n%n", createIndexMetadata.getIndexName(), sameNameRecord);
                return true;
            }
        }

        return false;
    }

    @Override
    public List<IndexRecord> getAllIndexRecords()
            throws IOException
    {
        return indexRecordManager.getIndexRecords();
    }

    @Override
    public IndexRecord getIndexRecord(String name)
            throws IOException
    {
        return indexRecordManager.lookUpIndexRecord(name);
    }

    @Override
    public void addIndexRecord(CreateIndexMetadata createIndexMetadata)
            throws IOException
    {
        indexRecordManager.addIndexRecord(
                createIndexMetadata.getIndexName(),
                createIndexMetadata.getUser(),
                createIndexMetadata.getTableName(),
                createIndexMetadata.getIndexColumns().stream().map(Map.Entry::getKey).toArray(String[]::new),
                createIndexMetadata.getIndexType(),
                createIndexMetadata.getPartitions());
    }

    @Override
    public void deleteIndex(String indexName)
            throws IOException
    {
        IndexRecord indexRecord = indexRecordManager.lookUpIndexRecord(indexName);
        Path toDelete = root.resolve(indexRecord.table).resolve(String.join(COLUMN_DELIMITER, indexRecord.columns)).resolve(indexRecord.indexType);

        if (!fs.exists(toDelete)) {
            return;
        }

        Lock lock = new FileBasedLock(fs, toDelete.getParent());
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                lock.unlock();
                try {
                    fs.close();
                }
                catch (IOException e) {
                    throw new UncheckedIOException("Error closing FileSystem Client: " + fs.getClass().getName(), e);
                }
            }));
            lock.lock();

            fs.deleteRecursively(toDelete);
        }
        finally {
            lock.unlock();
        }

        indexRecordManager.deleteIndexRecord(indexName);
        return;
    }

    private boolean notDirectory(Path path)
    {
        return !LOCAL_FS_CLIENT.isDirectory(path);
    }

    /**
     * Reads all files at the specified path recursively.
     * <br>
     * If the file extension matches a supported index type id, the index is loaded.
     * For example, if a file name is filename.bloom, then the file will be loaded
     * as a BloomIndex.
     *
     * @param path relative path to the index file or dir, if dir, it will be searched recursively (relative to the
     * root uri, if one was set)
     * @return an immutable mapping from all index files read to the corresponding index that was loaded
     * @throws IOException
     */
    private Map<String, Index> readIndexMap(String path)
            throws IOException
    {
        ImmutableMap.Builder<String, Index> result = ImmutableMap.builder();

        // get the absolute path to the file being read
        Path absolutePath = Paths.get(root.toString(), path);

        if (!fs.exists(absolutePath)) {
            return result.build();
        }

        try (Stream<Path> tarsOnRemote = fs.walk(absolutePath).filter(p -> p.toString().contains(".tar"))) {
            for (Path tarFile : (Iterable<Path>) tarsOnRemote::iterator) {
                try (TarArchiveInputStream i = new TarArchiveInputStream(fs.newInputStream(tarFile))) {
                    ArchiveEntry entry;
                    while ((entry = i.getNextEntry()) != null) {
                        if (!i.canReadEntryData(entry)) {
                            throw new FileSystemException("Unable to read archive entry: " + entry.toString());
                        }

                        String filename = entry.getName();

                        if (!filename.contains(".")) {
                            continue;
                        }

                        String indexType = filename.substring(filename.lastIndexOf('.') + 1);
                        Index index = HeuristicIndexFactory.createIndex(indexType);

                        index.deserialize(new CloseShieldInputStream(i));
                        LOG.debug("Loaded %s index from %s.", index.getId(), tarFile.toAbsolutePath());
                        result.put(tarFile.getParent().resolve(filename).toString(), index);
                    }
                }
            }
        }

        Map<String, Index> resultMap = result.build();

        return resultMap;
    }
}
