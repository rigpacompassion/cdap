/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.data.tools;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.dataset.DatasetSpecification;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.data2.datafabric.dataset.DatasetsUtil;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.dataset2.DatasetManagementException;
import co.cask.cdap.data2.dataset2.lib.table.MetricsTable;
import co.cask.cdap.data2.dataset2.lib.table.hbase.HBaseTableAdmin;
import co.cask.cdap.data2.util.TableId;
import co.cask.cdap.data2.util.hbase.HBaseTableUtil;
import co.cask.cdap.metrics.MetricsConstants;
import co.cask.cdap.metrics.process.KafkaConsumerMetaTable;
import co.cask.cdap.metrics.store.DefaultMetricDatasetFactory;
import co.cask.cdap.proto.Id;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.twill.filesystem.LocationFactory;
import org.apache.twill.kafka.client.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Upgrade kafka metrics meta table data
 */
public class MetricsKafkaUpgrader extends AbstractUpgrader {

  private static final Logger LOG = LoggerFactory.getLogger(DatasetUpgrader.class);
  private static final byte[] OFFSET_COLUMN = Bytes.toBytes("o");

  private final Configuration hConf;
  private final HBaseTableUtil hBaseTableUtil;
  private final DatasetFramework dsFramework;
  private final String kafkaTableName;
  private final KafkaConsumerMetaTable kafkaMetaTableDestination;

  @Inject
  public MetricsKafkaUpgrader(CConfiguration cConf, Configuration hConf, LocationFactory locationFactory,
                              HBaseTableUtil hBaseTableUtil, @Named("dsFramework") final DatasetFramework dsFramework) {
    super(locationFactory);
    this.hConf = hConf;
    this.hBaseTableUtil = hBaseTableUtil;
    this.dsFramework = dsFramework;
    this.kafkaTableName =  cConf.get(MetricsConstants.ConfigKeys.KAFKA_META_TABLE,
                                     MetricsConstants.DEFAULT_KAFKA_META_TABLE);
    this.kafkaMetaTableDestination = new DefaultMetricDatasetFactory(cConf, dsFramework).createKafkaConsumerMeta();
  }

  private MetricsTable getOrCreateKafkaTable(String tableName, DatasetProperties props) {
    MetricsTable table = null;
    // old kafka table is in the default namespace
    Id.DatasetInstance metricsDatasetInstanceId = Id.DatasetInstance.from(Constants.DEFAULT_NAMESPACE_ID, tableName);
    try {
      table = DatasetsUtil.getOrCreateDataset(dsFramework, metricsDatasetInstanceId,
                                              MetricsTable.class.getName(), props, null, null);
    } catch (Exception e) {
      LOG.error("Exception while creating table {}.", tableName, e);
    }
    return table;
  }

  @Override
  public void upgrade() throws Exception {
    // copy kafka off set from old table to new kafka metrics table

    String kafkaTableNameOld = Joiner.on(".").join(Constants.SYSTEM_NAMESPACE, kafkaTableName);
    byte[] columnFamily = getColumnFamily(kafkaTableNameOld);
    HTable hTable = getHTable(kafkaTableNameOld);

    LOG.info("Starting upgrade for table {}", Bytes.toString(hTable.getTableName()));
    // iterate old table and add those rows to the new kafkaConsumerMetaTable
    try {
      Scan scan = getScan(columnFamily);
      ResultScanner resultScanner = hTable.getScanner(scan);
      Result result;
      try {
        while ((result = resultScanner.next()) != null) {
          TopicPartition topicPartition =  getTopicPartition(result.getRow());
          if (topicPartition != null) {
            long value  = Bytes.toLong(result.getFamilyMap(columnFamily).get(OFFSET_COLUMN));
            kafkaMetaTableDestination.save(ImmutableMap.of(topicPartition, value));
          } else {
            LOG.warn("Invalid topic partition found");
          }
        }
        LOG.info("Successfully completed upgrade for table {}", Bytes.toString(hTable.getTableName()));
      } finally {
        resultScanner.close();
      }
    } catch (Exception e) {
      LOG.info("Exception during upgrading metrics-kafka table {}", e);
    } finally {
      hTable.close();
    }
  }

  private byte[] getColumnFamily(String tableName) throws DatasetManagementException {
    // add old table for getting dataset spec
    getOrCreateKafkaTable(tableName, DatasetProperties.EMPTY);
    DatasetSpecification specification =
      dsFramework.getDatasetSpec(Id.DatasetInstance.from(Constants.DEFAULT_NAMESPACE_ID, tableName));
    return HBaseTableAdmin.getColumnFamily(specification);
  }

  private HTable getHTable(String tableName) throws IOException {
    TableId kafkaTableOld = TableId.from(Constants.DEFAULT_NAMESPACE, tableName);
    return hBaseTableUtil.createHTable(hConf, kafkaTableOld);
  }

  private Scan getScan(byte[] columnFamily) throws IOException {
    Scan scan = new Scan();
    scan.setTimeRange(0, HConstants.LATEST_TIMESTAMP);
    scan.addFamily(columnFamily);
    scan.setMaxVersions(1); // we only need to see one version of each row
    return scan;
  }

  private TopicPartition getTopicPartition(byte[] rowKey) {
    // convert rowkey to string, split by "DOT" , create TopicPartition
    String[] tp = Bytes.toString(rowKey).split("\\.");
    if (tp.length == 2) {
      return new TopicPartition(tp[0], Integer.parseInt(tp[1]));
    }
    return null;
  }
}
