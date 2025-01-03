// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.


suite("test_nereids_admin_check_tablet") {
    //cloud-mode
    if (isCloudMode()) {
        return
    }
    def table = "test_nereids_admin_check_tablet"
    // create table and insert data
    sql """ drop table if exists ${table} force"""
    sql """
    create table ${table} (
        `id` int(11),
        `name` varchar(128),
        `da` date
    )
    engine=olap
    duplicate key(id)
    partition by range(da)(
        PARTITION p3 VALUES LESS THAN ('2023-01-01'),
        PARTITION p4 VALUES LESS THAN ('2024-01-01'),
        PARTITION p5 VALUES LESS THAN ('2025-01-01')
    )
    distributed by hash(id) buckets 2
    properties(
        "replication_num"="1",
        "light_schema_change"="true"
    );
    """

    def tabletId
    def tablets = sql_return_maparray "SHOW TABLETS FROM ${table}"

    for (def tablet : tablets) {
        //get any partition ID.
        tabletId = tablet.TabletId;
        break;
    }

    logger.info("${tabletId}");

    checkNereidsExecute("ADMIN CHECK TABLET (${tabletId}) PROPERTIES('type' = 'consistency')");

}

