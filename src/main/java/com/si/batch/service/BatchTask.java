package com.si.batch.service;

import com.si.batch.common.BatchContext;
import java.sql.Connection;

public interface BatchTask {

    int execute(Connection conn, BatchContext context) throws Exception;
}