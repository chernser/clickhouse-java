package com.clickhouse.client.http;

import java.util.UUID;

import com.clickhouse.client.ClickHouseClient;
import com.clickhouse.client.ClickHouseCredentials;
import com.clickhouse.client.ClickHouseFormat;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.ClickHouseParameterizedQuery;
import com.clickhouse.client.ClickHouseProtocol;
import com.clickhouse.client.ClickHouseRecord;
import com.clickhouse.client.ClickHouseRequest;
import com.clickhouse.client.ClickHouseResponse;
import com.clickhouse.client.ClickHouseResponseSummary;
import com.clickhouse.client.ClickHouseVersion;
import com.clickhouse.client.ClientIntegrationTest;
import com.clickhouse.client.config.ClickHouseClientOption;
import com.clickhouse.client.data.ClickHouseStringValue;

import org.testng.Assert;
import org.testng.annotations.Test;

public class ClickHouseHttpClientTest extends ClientIntegrationTest {
    @Override
    protected ClickHouseProtocol getProtocol() {
        return ClickHouseProtocol.HTTP;
    }

    @Override
    protected Class<? extends ClickHouseClient> getClientClass() {
        return ClickHouseHttpClient.class;
    }

    @Test(groups = { "integration" })
    @Override
    public void testMutation() throws Exception {
        super.testMutation();

        ClickHouseNode server = getServer();
        ClickHouseClient.send(server, "drop table if exists test_http_mutation",
                "create table test_http_mutation(a String, b Nullable(Int64))engine=Memory").get();
        try (ClickHouseClient client = getClient();
                ClickHouseResponse response = client.connect(server).set("send_progress_in_http_headers", 1)
                        .query("insert into test_http_mutation select toString(number), number from numbers(1)")
                        .execute().get()) {
            ClickHouseResponseSummary summary = response.getSummary();
            Assert.assertEquals(summary.getWrittenRows(), 1);
        }
    }

    @Test(groups = { "integration" })
    public void testLogComment() throws Exception {
        ClickHouseNode server = getServer(ClickHouseProtocol.HTTP);
        String uuid = UUID.randomUUID().toString();
        try (ClickHouseClient client = ClickHouseClient.newInstance()) {
            ClickHouseRequest<?> request = client.connect(server).format(ClickHouseFormat.RowBinaryWithNamesAndTypes);
            try (ClickHouseResponse resp = request
                    .query("select version()").execute().get()) {
                if (!ClickHouseVersion.of(resp.firstRecord().getValue(0).asString()).check("[21.2,)")) {
                    return;
                }
            }
            try (ClickHouseResponse resp = request
                    .option(ClickHouseClientOption.LOG_LEADING_COMMENT, true)
                    .query("-- select something\r\nselect 1", uuid).execute().get()) {
            }

            try (ClickHouseResponse resp = request
                    .option(ClickHouseClientOption.LOG_LEADING_COMMENT, true)
                    .query("SYSTEM FLUSH LOGS", uuid).execute().get()) {
            }

            try (ClickHouseResponse resp = request
                    .option(ClickHouseClientOption.LOG_LEADING_COMMENT, true)
                    .query(ClickHouseParameterizedQuery
                            .of(request.getConfig(), "select log_comment from system.query_log where query_id = :qid"))
                    .params(ClickHouseStringValue.of(uuid)).execute().get()) {
                int counter = 0;
                for (ClickHouseRecord r : resp.records()) {
                    Assert.assertEquals(r.getValue(0).asString(), "select something");
                    counter++;
                }
                Assert.assertEquals(counter, 2);
            }
        }
    }

    @Test(groups = { "integration" })
    public void testPost() throws Exception {
        ClickHouseNode server = getServer(ClickHouseProtocol.HTTP);

        try (ClickHouseClient client = ClickHouseClient.builder()
                .defaultCredentials(ClickHouseCredentials.fromUserAndPassword("foo", "bar")).build()) {
            // why no detailed error message for this: "select 1，2"
            try (ClickHouseResponse resp = client.connect(server).compressServerResponse(false)
                    .format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                    .query("select 1,2").execute().get()) {
                int count = 0;
                for (ClickHouseRecord r : resp.records()) {
                    Assert.assertEquals(r.getValue(0).asInteger(), 1);
                    Assert.assertEquals(r.getValue(1).asInteger(), 2);
                    count++;
                }

                Assert.assertEquals(count, 1);
            }

            // reuse connection
            try (ClickHouseResponse resp = client.connect(server).format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                    .query("select 3,4").execute().get()) {
                int count = 0;
                for (ClickHouseRecord r : resp.records()) {
                    Assert.assertEquals(r.getValue(0).asInteger(), 3);
                    Assert.assertEquals(r.getValue(1).asInteger(), 4);
                    count++;
                }

                Assert.assertEquals(count, 1);
            }
        }
    }
}
