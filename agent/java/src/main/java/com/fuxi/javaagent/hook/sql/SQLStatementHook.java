/*
 * Copyright 2017-2018 Baidu Inc.
 *
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

package com.fuxi.javaagent.hook.sql;

import com.fuxi.javaagent.HookHandler;
import com.fuxi.javaagent.plugin.checker.CheckParameter;
import com.fuxi.javaagent.plugin.js.engine.JSContext;
import com.fuxi.javaagent.plugin.js.engine.JSContextFactory;
import com.fuxi.javaagent.tool.Reflection;
import org.mozilla.javascript.Scriptable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.util.Arrays;

/**
 * Created by zhuming01 on 7/18/17.
 * All rights reserved
 */
public class SQLStatementHook extends AbstractSqlHook {

    /**
     * (none-javadoc)
     *
     * @see com.fuxi.javaagent.hook.AbstractClassHook#getType()
     */
    @Override
    public String getType() {
        return "sql";
    }

    @Override
    public boolean isClassMatched(String className) {
        /* MySQL */
        if ("com/mysql/jdbc/StatementImpl".equals(className)
                || "com/mysql/cj/jdbc/StatementImpl".equals(className)) {
            this.type = SQL_TYPE_MYSQL;
            this.exceptions = new String[]{"java/sql/SQLException"};
            return true;
        }

        /* SQLite */
        if ("org/sqlite/Stmt".equals(className)
                || "org/sqlite/jdbc3/JDBC3Statement".equals(className)) {
            this.type = SQL_TYPE_SQLITE;
            this.exceptions = new String[]{"java/sql/SQLException"};
            return true;
        }

        /* Oracle */
        if ("oracle/jdbc/driver/OracleStatement".equals(className)) {
            this.type = SQL_TYPE_ORACLE;
            this.exceptions = new String[]{"java/sql/SQLException"};
            return true;
        }

        /* SQL Server */
        if ("com/microsoft/sqlserver/jdbc/SQLServerStatement".equals(className)) {
            this.type = SQL_TYPE_SQLSERVER;
            this.exceptions = new String[]{"com/microsoft/sqlserver/jdbc/SQLServerException"};
            return true;
        }

        /* PostgreSQL */
        if ("org/postgresql/jdbc/PgStatement".equals(className)
                || "org/postgresql/jdbc1/AbstractJdbc1Statement".equals(className)
                || "org/postgresql/jdbc2/AbstractJdbc2Statement".equals(className)
                || "org/postgresql/jdbc3/AbstractJdbc3Statement".equals(className)
                || "org/postgresql/jdbc3/AbstractJdbc3Statement".equals(className)
                || "org/postgresql/jdbc3g/AbstractJdbc3gStatement".equals(className)
                || "org/postgresql/jdbc4/AbstractJdbc4Statement".equals(className)) {
            this.type = SQL_TYPE_PGSQL;
            this.exceptions = new String[]{"java/sql/SQLException"};
            return true;
        }

        /* DB2 */
        if (className.startsWith("com/ibm/db2/jcc/am")) {
            this.type = SQL_TYPE_DB2;
            this.exceptions = new String[]{"java/sql/SQLException"};
            return true;
        }

        return false;
    }

    @Override
    protected MethodVisitor hookMethod(int access, String name, String desc, String signature, String[] exceptions, MethodVisitor mv) {
        boolean hook = false;
        // 适配 db2 hook 点
        if (this.type.equals(SQL_TYPE_DB2) && getInterfaces() != null) {
            if (isExecutableSqlMethod(name, desc) && getInterfaces().length > 2) {
                for (String inter : getInterfaces()) {
                    if (inter != null && inter.equals("com/ibm/db2/jcc/DB2Statement")) {
                        hook = true;
                        break;
                    }
                }
            } else if (isQueryResultMethod(name, desc) && getInterfaces().length > 3) {
                for (String inter : getInterfaces()) {
                    if (inter != null && inter.equals("com/ibm/db2/jcc/DB2ResultSet")) {
                        SQLResultSetHook resultSetHook = new SQLResultSetHook();
                        resultSetHook.setType(this.type);
                        resultSetHook.setExceptions(this.exceptions);
                        return resultSetHook.hookMethod(access, name, desc, signature, exceptions, mv);
                    }
                }
            }

        } else {
            hook = isExecutableSqlMethod(name, desc);
        }
        return hook ? new AdviceAdapter(Opcodes.ASM5, mv, access, name, desc) {
            @Override
            protected void onMethodEnter() {
                push(type);
                loadThis();
                loadArg(0);
                invokeStatic(Type.getType(SQLStatementHook.class),
                        new Method("checkSQL", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)V"));
            }
        } : mv;
    }

    public boolean isExecutableSqlMethod(String name, String desc) {
        boolean result = false;
        if (name.equals("execute") && Arrays.equals(exceptions, this.exceptions)) {
            if (desc.equals("(Ljava/lang/String;)Z")
                    || desc.equals("(Ljava/lang/String;I)Z")
                    || desc.equals("(Ljava/lang/String;[I)Z")
                    || desc.equals("(Ljava/lang/String;[Ljava/lang/String;)Z")) {
                result = true;
            }
        } else if (name.equals("executeUpdate") && Arrays.equals(exceptions, this.exceptions)) {
            if (desc.equals("(Ljava/lang/String;)I")
                    || desc.equals("(Ljava/lang/String;I)I")
                    || desc.equals("(Ljava/lang/String;[I)I")
                    || desc.equals("(Ljava/lang/String;[Ljava/lang/String;)I")) {
                result = true;
            }
        } else if (name.equals("executeQuery") && Arrays.equals(exceptions, this.exceptions)) {
            if (desc.equals("(Ljava/lang/String;)Ljava/sql/ResultSet;")) {
                result = true;
            }
        } else if (name.equals("addBatch") && Arrays.equals(exceptions, this.exceptions)) {
            if (desc.equals("(Ljava/lang/String;)V")) {
                result = true;
            }
        }
        return result;
    }

    public boolean isQueryResultMethod(String name, String desc) {
        return (name.equals("next") && desc.equals("()Z") && Arrays.equals(exceptions, this.exceptions));
    }

    public static String getSqlConnectionId(String type, Object statement) {
        String id = null;
        try {
            if (type.equals(SQLStatementHook.SQL_TYPE_MYSQL)) {
                id = Reflection.getField(statement, "connectionId").toString();
            } else if (type.equals(SQLStatementHook.SQL_TYPE_ORACLE)) {
                Object connection = Reflection.getField(statement, "connection");
                id = Reflection.getField(connection, "ociConnectionPoolConnID").toString();
            } else if (type.equals(SQLStatementHook.SQL_TYPE_SQLSERVER)) {
                Object connection = Reflection.invokeMethod(statement, "getConnection", new Class[]{});
                id = Reflection.getField(connection, "clientConnectionId").toString();
            }
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SQL语句检测
     *
     * @param stmt sql语句
     */
    public static void checkSQL(String server, Object statement, String stmt) {
        if (stmt != null && !stmt.isEmpty()) {
            JSContext cx = JSContextFactory.enterAndInitContext();
            Scriptable params = cx.newObject(cx.getScope());
            String connectionId = getSqlConnectionId(server, statement);
            if (connectionId != null) {
                params.put(server + "_connection_id", params, connectionId);
            }
            params.put("server", params, server);
            params.put("query", params, stmt);

            HookHandler.doCheck(CheckParameter.Type.SQL, params);
        }
    }

}
