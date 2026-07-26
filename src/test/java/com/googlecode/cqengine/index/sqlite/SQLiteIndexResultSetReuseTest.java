// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.index.sqlite;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.sqlite.support.DBUtils;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.query.simple.FilterQuery;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.testutil.Car;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Iterator;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.testutil.TestAssertions.assertFalse;
import static com.googlecode.cqengine.testutil.TestAssertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SQLiteIndexResultSetReuseTest {

    @Test
    public void secondIteratorIsTrackedAfterFirstIteratorExhausts() throws Exception {
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        Connection connection = mock(Connection.class);
        PreparedStatement firstStatement = mock(PreparedStatement.class);
        PreparedStatement secondStatement = mock(PreparedStatement.class);
        java.sql.ResultSet firstJdbcResults = mock(java.sql.ResultSet.class);
        java.sql.ResultSet secondJdbcResults = mock(java.sql.ResultSet.class);
        String table = "\"cqtbl_"
                + DBUtils.createSQLiteIndexTableNameV2(Car.FEATURES.getAttributeName(), "") + "\"";
        String sql = "SELECT DISTINCT objectKey FROM " + table + " WHERE value = ?;";

        when(connectionManager.getConnection(any(SQLiteIndex.class), any(QueryOptions.class)))
                .thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(firstStatement, secondStatement);
        when(firstStatement.executeQuery()).thenReturn(firstJdbcResults);
        when(secondStatement.executeQuery()).thenReturn(secondJdbcResults);
        when(firstJdbcResults.getStatement()).thenReturn(firstStatement);
        when(secondJdbcResults.getStatement()).thenReturn(secondStatement);
        when(firstJdbcResults.next()).thenReturn(false);

        QueryOptions queryOptions = new QueryOptions();
        queryOptions.put(ConnectionManager.class, connectionManager);
        SimpleAttribute<Integer, Car> idToObject = new SimpleAttribute<Integer, Car>("carFromId") {
            @Override
            public Car getValue(Integer carId, QueryOptions queryOptions) {
                return null;
            }
        };
        ResultSet<Car> results = new SQLiteIndex<String, Car, Integer>(
                Car.FEATURES, Car.CAR_ID, idToObject, "")
                .retrieve(equal(Car.FEATURES, "abs"), queryOptions);

        Iterator<Car> first = results.iterator();
        assertFalse(first.hasNext());
        Iterator<Car> second = results.iterator();
        assertNotNull(second);
        results.close();

        verify(firstJdbcResults, times(1)).close();
        verify(firstStatement, times(1)).close();
        verify(secondJdbcResults, times(1)).close();
        verify(secondStatement, times(1)).close();
    }

    @Test
    public void filterQuerySecondIteratorIsTrackedAfterFirstIteratorExhausts() throws Exception {
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        Connection connection = mock(Connection.class);
        Statement firstStatement = mock(Statement.class);
        Statement secondStatement = mock(Statement.class);
        java.sql.ResultSet firstJdbcResults = mock(java.sql.ResultSet.class);
        java.sql.ResultSet secondJdbcResults = mock(java.sql.ResultSet.class);
        String table = "\"cqtbl_"
                + DBUtils.createSQLiteIndexTableNameV2(Car.FEATURES.getAttributeName(), "") + "\"";
        String sql = "SELECT objectKey, value FROM " + table + " ORDER BY objectKey;";

        when(connectionManager.getConnection(any(SQLiteIndex.class), any(QueryOptions.class)))
                .thenReturn(connection);
        when(connection.createStatement()).thenReturn(firstStatement, secondStatement);
        when(firstStatement.executeQuery(sql)).thenReturn(firstJdbcResults);
        when(secondStatement.executeQuery(sql)).thenReturn(secondJdbcResults);
        when(firstJdbcResults.getStatement()).thenReturn(firstStatement);
        when(secondJdbcResults.getStatement()).thenReturn(secondStatement);
        when(firstJdbcResults.next()).thenReturn(false);

        QueryOptions queryOptions = new QueryOptions();
        queryOptions.put(ConnectionManager.class, connectionManager);
        SimpleAttribute<Integer, Car> idToObject = new SimpleAttribute<Integer, Car>("carFromId") {
            @Override
            public Car getValue(Integer carId, QueryOptions queryOptions) {
                return null;
            }
        };
        FilterQuery<Car, String> query = SQLiteIndexTest.mockFilterQuery();
        ResultSet<Car> results = new SQLiteIndex<String, Car, Integer>(
                Car.FEATURES, Car.CAR_ID, idToObject, "")
                .retrieve(query, queryOptions);

        Iterator<Car> first = results.iterator();
        assertFalse(first.hasNext());
        Iterator<Car> second = results.iterator();
        assertNotNull(second);
        results.close();

        verify(firstJdbcResults, times(1)).close();
        verify(firstStatement, times(1)).close();
        verify(secondJdbcResults, times(1)).close();
        verify(secondStatement, times(1)).close();
    }
}
