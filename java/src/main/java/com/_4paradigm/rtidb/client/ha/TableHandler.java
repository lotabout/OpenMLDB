package com._4paradigm.rtidb.client.ha;

import com._4paradigm.rtidb.client.schema.ColumnDesc;
import com._4paradigm.rtidb.client.schema.ColumnType;
import com._4paradigm.rtidb.client.type.DataType;
import com._4paradigm.rtidb.common.Common;
import com._4paradigm.rtidb.ns.NS.TableInfo;
import com._4paradigm.rtidb.type.Type;
import rtidb.blobserver.BlobServer;

import java.util.*;

public class TableHandler {

    private TableInfo tableInfo;
    private PartitionHandler[] partitions;
    private Map<Integer, List<Integer>> indexes = new TreeMap<Integer, List<Integer>>();
    private Map<Integer, List<Integer>> indexTsMap = new TreeMap<Integer, List<Integer>>();
    private Map<String, List<String>> keyMap = new TreeMap<String, List<String>>();
    private Map<String, Integer> schemaPos = new TreeMap<>();
    private List<ColumnDesc> schema = new ArrayList<ColumnDesc>();
    private Map<Integer, List<ColumnDesc>> schemaMap = new TreeMap<>();
    private ReadStrategy readStrategy = ReadStrategy.kReadLeader;
    private boolean hasTsCol = false;
    private String autoGenPkName = "";
    private int formatVersion = 0;
    private List<Integer> partitionKeyList = new ArrayList<>();
    private boolean isObjectStore = false;
    private BlobServer blobServer = null;
    private List<Integer> blobIdxList = new ArrayList<Integer>();
    private Map<Integer, List<ColumnDesc>> versions = new HashMap<>();
    private Map<Integer, Integer> schemaToVer = new HashMap<>();
    private int currentSchemaVersion = 1;

    public int getFormatVersion() {
        return formatVersion;
    }

    private Map<String, DataType> nameTypeMap = new HashMap<>();
    public TableHandler(TableInfo tableInfo) {
        this.tableInfo = tableInfo;
        int schemaSize = 0;
        int index = 0;
        formatVersion = tableInfo.getFormatVersion();
        Map<String, Integer> tsPos = new HashMap<String, Integer>();
        if (tableInfo.getColumnDescV1Count() > 0) {
            schemaSize = tableInfo.getColumnDescV1Count();
            for (int i = 0; i< tableInfo.getColumnDescV1Count(); i++) {
                com._4paradigm.rtidb.common.Common.ColumnDesc cd = tableInfo.getColumnDescV1(i);
                ColumnDesc ncd = new ColumnDesc();
                ncd.setName(cd.getName());
                ncd.setDataType(DataType.valueFrom(cd.getDataType()));
                ncd.setNotNull(cd.getNotNull());
                if (!tableInfo.hasTableType() ||
                        tableInfo.getTableType() == Type.TableType.kTimeSeries) {
                    ncd.setAddTsIndex(cd.getAddTsIdx());
                    if (cd.getIsTsCol()) {
                        hasTsCol = true;
                        tsPos.put(cd.getName(), i);
                    }
                    ncd.setTsCol(cd.getIsTsCol());
                    ncd.setType(ColumnType.valueFrom(cd.getType()));
                }
                if (cd.getDataType() == Type.DataType.kBlob) {
                    blobIdxList.add(i);
                }
                schema.add(ncd);
                if (cd.getAddTsIdx()) {
                    List<Integer> indexList = new ArrayList<Integer>();
                    indexList.add(i);
                    indexes.put(index, indexList);
                    List<String> nameList = new ArrayList<String>();
                    nameList.add(cd.getName());
                    keyMap.put(cd.getName(), nameList);
                    index++;
                }
                schemaPos.put(cd.getName(), i);
            }
            versions.put(1, this.schema);
        } else {
            schemaSize = tableInfo.getColumnDescCount();
            for (int i = 0; i < schemaSize; i++) {
                com._4paradigm.rtidb.ns.NS.ColumnDesc cd = tableInfo.getColumnDesc(i);
                ColumnDesc ncd = new ColumnDesc();
                ncd.setName(cd.getName());
                ncd.setAddTsIndex(cd.getAddTsIdx());
                ncd.setTsCol(false);
                ncd.setType(ColumnType.valueFrom(cd.getType()));
                schema.add(ncd);
                if (cd.getAddTsIdx()) {
                    List<Integer> list = new ArrayList<Integer>();
                    list.add(i);
                    indexes.put(index, list);
                    index++;
                }
            }
        }
        for (ColumnDesc cd : schema) {
            nameTypeMap.put(cd.getName(), cd.getDataType());
        }
        if (tableInfo.getAddedColumnDescCount() > 0) {
            List<ColumnDesc> tempList = new ArrayList<ColumnDesc>(schema);
            for (int i = 0; i < tableInfo.getAddedColumnDescCount(); i++) {
                com._4paradigm.rtidb.common.Common.ColumnDesc cd = tableInfo.getAddedColumnDesc(i);
                ColumnDesc ncd = new ColumnDesc();
                ncd.setName(cd.getName());
                ncd.setDataType(DataType.valueFrom(cd.getDataType()));
                if (!tableInfo.hasTableType() ||
                        tableInfo.getTableType() == Type.TableType.kTimeSeries) {
                    ncd.setType(ColumnType.valueFrom(cd.getType()));
                } else {
                    ncd.setDataType(DataType.valueFrom(cd.getDataType()));
                    ncd.setNotNull(cd.getNotNull());
                }
                tempList.add(ncd);
                schemaMap.put(schemaSize + i + 1, new ArrayList<>(tempList));
                nameTypeMap.put(ncd.getName(), ncd.getDataType());
                schemaPos.put(ncd.getName(), schemaPos.size() + i);
            }
        }

        if (tableInfo.getColumnDescV1Count() > 0 && tableInfo.getColumnKeyCount() > 0) {
            indexes.clear();
            keyMap.clear();
            index = 0;
            Set<String> indexSet = new HashSet<String>();
            for (com._4paradigm.rtidb.common.Common.ColumnKey ck : tableInfo.getColumnKeyList()) {
                List<Integer> indexList = new ArrayList<Integer>();
                List<Integer> tsList = new ArrayList<Integer>();
                List<String> nameList = new ArrayList<String>();
                for (String colName : ck.getColNameList()) {
                    indexList.add(schemaPos.get(colName));
                    nameList.add(colName);
                }
                for (String tsName : ck.getTsNameList()) {
                    tsList.add(tsPos.get(tsName));
                }
                if (indexList.isEmpty()) {
                    String key = ck.getIndexName();
                    indexList.add(schemaPos.get(key));
                    nameList.add(key);
                }
                if (indexSet.contains(ck.getIndexName())) {
                    continue;
                }
                indexSet.add(ck.getIndexName());
                indexes.put(index, indexList);
                keyMap.put(ck.getIndexName(), nameList);
                if (!tsList.isEmpty()) {
                    indexTsMap.put(index, tsList);
                } else if (!tsPos.isEmpty()) {
                    for (Integer curTsPos : tsPos.values()) {
                        tsList.add(curTsPos);
                    }
                    for (Integer cur_index : indexes.keySet()) {
                        indexTsMap.put(index, tsList);
                    }
                }
                index++;
            }
        } else {
            if (!tsPos.isEmpty()) {
                List<Integer> tsList = new ArrayList<Integer>();
                for (Integer curTsPos : tsPos.values()) {
                    tsList.add(curTsPos);
                }
                for (Integer cur_index : indexes.keySet()) {
                    indexTsMap.put(index, tsList);
                }
            }
        }
        for(Common.VersionPair ver : tableInfo.getSchemaVersionsList()) {
            List<ColumnDesc> schema = this.schemaMap.get(ver.getFieldCount());
            if (schema == null) {
                continue;
            }
            versions.put(ver.getId(), schema);
            schemaToVer.put(ver.getFieldCount(), ver.getId());
            if (ver.getId() > this.currentSchemaVersion) {
                this.currentSchemaVersion = ver.getId();
            }
        }

        if (tableInfo.hasTableType()) {
            if (tableInfo.getTableType() == Type.TableType.kRelational) {
                for (int i = 0; i < tableInfo.getColumnKeyList().size(); i++) {
                    Common.ColumnKey columnKey = tableInfo.getColumnKeyList().get(i);
                    if (columnKey.hasIndexType() && columnKey.getIndexType() == Type.IndexType.kAutoGen) {
                        autoGenPkName = columnKey.getColName(0);
                        break;
                    }
                }
            } else if (tableInfo.getTableType() == Type.TableType.kObjectStore) {
                this.isObjectStore = true;
            }
        }

        for (int idx = 0; idx < tableInfo.getPartitionKeyCount(); idx++) {
            Object value = schemaPos.get(tableInfo.getPartitionKey(idx));
            if (value != null) {
                partitionKeyList.add((Integer) value);
            }
        }
    }
    
    public ReadStrategy getReadStrategy() {
        return readStrategy;
    }

    public void setReadStrategy(ReadStrategy readStrategy) {
        this.readStrategy = readStrategy;
    }

    public TableHandler(List<ColumnDesc> schema, List<Common.VersionPair> idxVersions) {
        int index = 0;
        int col_num = 0;
        for (ColumnDesc col : schema) {
            if (col.isAddTsIndex()) {
                List<Integer> list = new ArrayList<Integer>();
                list.add(col_num);
                indexes.put(index, list);
                index ++;
            }
            col_num++;
        }
        this.schema = schema;
        for(Common.VersionPair ver : idxVersions) {
            List<ColumnDesc> versionSchema = schema.subList(0, ver.getFieldCount());
            versions.put(ver.getId(), versionSchema);
            if (ver.getId() > this.currentSchemaVersion) {
                this.currentSchemaVersion = ver.getId();
            }
        }
    }
    
    public TableHandler() {}
    public PartitionHandler getHandler(int pid) {
        if (pid >= partitions.length) {
            return null;
        }
        return partitions[pid];
    }

    public void setPartitions(PartitionHandler[] partitions) {
        this.partitions = partitions;
    }

    public void setBlobServer(BlobServer bs) {
        this.blobServer = bs;
    }

    public BlobServer getBlobServer() {
        return this.blobServer;
    }

    public  boolean IsObjectTable() {
        return this.isObjectStore;
    }

    public List<Integer> GetPartitionKeyList() {
        return this.partitionKeyList;
    }

    public TableInfo getTableInfo() {
        return tableInfo;
    }

    public List<Integer> getBlobIdxList() {
        return this.blobIdxList;
    }

    public void setTableInfo(TableInfo tableInfo) {
        this.tableInfo = tableInfo;
    }

    public PartitionHandler[] getPartitions() {
        return partitions;
    }

    public Map<Integer, List<Integer>> getIndexes() {
        return indexes;
    }

    public Map<Integer, List<Integer>> getIndexTsMap() {
        return indexTsMap;
    }

    public Map<String, List<String>> getKeyMap() {
        return keyMap;
    }

    public List<ColumnDesc> getSchema() {
        return schema;
    }

    public enum ReadStrategy {
        kReadLeader,
        kReadFollower,
        kReadLocal,
        kReadRandom
    }

    public Map<String, Integer> getSchemaPos() {
        return schemaPos;
    }

    public boolean hasTsCol() {
        return hasTsCol;
    }

    public Map<Integer, List<ColumnDesc>> getSchemaMap() {
        return schemaMap;
    }

    public String getAutoGenPkName() {
        return autoGenPkName;
    }

    public void setAutoGenPkName(String autoGenPkName) {
        this.autoGenPkName = autoGenPkName;
    }

    public Map<String, DataType> getNameTypeMap() {
        return nameTypeMap;
    }

    public Map<Integer, List<ColumnDesc>> getVersions() {
        return versions;
    }

    public Map<Integer, Integer> getSchemaToVer() {
        return schemaToVer;
    }

    public Integer getVerByRowLength(Integer rowLength) {
        return schemaToVer.get(rowLength);
    }

    public List<ColumnDesc> getSchemaByVer(Integer ver) {
        return versions.get(ver);
    }

    public int getCurrentSchemaVer() {
        return this.currentSchemaVersion;
    }
}
