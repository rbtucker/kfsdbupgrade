/*
 * Copyright 2014 The Kuali Foundation
 * 
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.opensource.org/licenses/ecl2.php
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ua.utility.kfsdbupgrade;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import liquibase.FileSystemFileOpener;
import liquibase.Liquibase;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

public class App {
    private static final int MAINTENANCE_DOCUMENT_UPDATE_BATCH_SIZE = 1000;
    
    private static final SimpleDateFormat DF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final String UNDERLINE = "--------------------------------------------------------------------------------------------------------------------------";
    private static final String ERROR = "************************************************* error *************************************************";
    private static final String HEADER1 = "================================================ ? ================================================";
    private static final String INDEX_NAME_TEMPLATE = "[table-name]I{index}";
    private static String upgradeRoot;
    private List<String> upgradeFolders;
    private Properties properties;
    private Map<String, List<String>> upgradeFiles;

    public static void main(final String args[]) {
        if (args.length == 1) {
            new App(args[0]);
        } else {
            System.out.println("usage: java -Xmx500m -jar kfsdbupgrade.jar <property-file-path>");
        }
    }

    public App(String propertyFileName) {
        properties = loadProperties(propertyFileName);
        if (properties != null) {
            upgradeRoot = properties.getProperty("upgrade-base-directory");
            upgradeFolders = loadList(properties.getProperty("upgrade-folders"));
            upgradeFiles = loadFolderFileMap("files-");

            Connection conn1 = null;
            Connection conn2 = null;
            Statement stmt = null;
            boolean success = false;
            try {
                conn1 = getUpgradeConnection();
                conn2 = getUpgradeConnection();
                conn2.setAutoCommit(true);
                stmt = conn2.createStatement();
                stmt.execute("ALTER SESSION ENABLE PARALLEL DML");
                stmt.close();
                stmt = conn1.createStatement();
                writeOut("Starting KFS database upgrade process...");
                writeOut("");

                if (doInitialProcessing(conn1, stmt)) {
                    doCommit(conn1);
                    if (doUpgrade(conn1, conn2, stmt)) {
                        success = true;
                    }
                }

                if (success) {
                    doCommit(conn1);
                    stmt.close();
                    stmt = conn2.createStatement();
                    dropTempTables(conn2, stmt);
                    runMiscSql(conn2, stmt);
                    populateProcurementCardTable(conn1);
                    updatePurchasingStatuses(conn1);
                    createExistingIndexes(conn2, stmt);
                    createPublicSynonyms(conn2, stmt);
                    createForeignKeyIndexes(conn2, stmt);
                    createDocumentSearchEntries(conn2, stmt);
                    if (StringUtils.equalsIgnoreCase(properties.getProperty("run-maintenance-document-conversion"), "true")) {
                        convertMaintenanceDocuments(conn1);
                    }
                    writeOut("");
                    writeHeader1("upgrade completed successfully");
                }
            } 
            
            catch (Exception ex) {
                writeOut(ex);
            } 
            
            finally {
                closeDbObjects(conn1, stmt, null);
                closeDbObjects(conn2, null, null);
            }
        } else {
            System.out.println("invalid properties file: " + propertyFileName);
        }
        
        System.exit(0);
    }
    
    private List<String> loadList(String input) {
        List<String> retval = new ArrayList<String>();
        if (StringUtils.isNotBlank(input)) {
            StringTokenizer st = new StringTokenizer(input, ",");

            while (st.hasMoreTokens()) {
                retval.add(st.nextToken().trim());
            }
        }
        return retval;
    }

    private void dropTempTables(Connection conn, Statement stmt) {
        writeHeader2("Dropping temporary tables");

        List<String> tables = new ArrayList<String>();
        ResultSet res = null;
        try {
            res = stmt.executeQuery("select table_name from user_tables where table_name like 'OLD_%' or table_name like 'TEMP_%'");

            while (res.next()) {
                tables.add(res.getString(1));
            }

            for (String t : tables) {
                try {
                    stmt.execute("drop table " + t);
                } catch (Exception ex) {
                    writeLog("failed to drop temp table " + t);
                }
            }
        } catch (Exception ex) {
            writeLog(ex);
        } finally {
            closeDbObjects(null, null, res);
        }
    }

    private Map<String, List<String>> loadFolderFileMap(String prefix) {
        Map<String, List<String>> retval = new HashMap<String, List<String>>();

        for (Entry e : properties.entrySet()) {
            String key = (String) e.getKey();
            if (key.startsWith(prefix)) {
                String folder = key.substring(prefix.length());
                retval.put(folder, loadList((String) e.getValue()));
            }
        }

        return retval;
    }

    private Properties loadProperties(String fname) {
        Properties retval = null;
        FileReader reader = null;

        try {
            reader = new FileReader(fname);

            retval = new Properties();
            retval.load(reader);
            if (StringUtils.isBlank(retval.getProperty("database-url"))) {
                retval = null;
            }
        } catch (Exception ex) {
            retval = null;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception ex) {
            };
        }

        return retval;
    }

    private void doRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (Exception ex) {
            writeOut(ex);
        }
    }

    private boolean doCommit(Connection conn) {
        boolean retval = true;
        try {
            conn.commit();
        } catch (Exception ex) {
            writeOut(ex);
            retval = false;
        }

        return retval;
    }

    private boolean runSqlFile(Connection conn, Statement stmt, File f, String delimiter) {
        boolean retval = true;
        writeHeader2("processing sql file " + f.getPath());
        List<String> sqlStatements = getSqlStatements(f);

        if (!sqlStatements.isEmpty()) {
            for (String sql : sqlStatements) {
                writeLog(sql);
                if (!executeSql(conn, stmt, sql)) {
                    retval = false;
                    break;
                }
            }

            if (retval) {
                retval = doCommit(conn);
            } else {
                doRollback(conn);
            }
        } else {
            retval = false;
            writeOut(new Exception("no sql statements found"));
        }

        return retval;
    }

    private List<String> getSqlStatements(File f) {
        List<String> retval = new ArrayList<String>();
        LineNumberReader lnr = null;

        try {
            lnr = new LineNumberReader(new FileReader(f));
            String line = null;
            StringBuilder sql = new StringBuilder(512);

            while ((line = lnr.readLine()) != null) {
                if (StringUtils.isNotBlank(line) && !line.trim().startsWith("--")) {
                    line = line.trim();
                    if (line.equals("/") || line.equals(";")) {
                        if (sql.length() > 0) {
                            retval.add(sql.toString());
                            sql.setLength(0);
                        }
                    } else if (line.endsWith("/") || line.endsWith(";")) {
                        sql.append(" ");
                        sql.append(line.substring(0, line.length() - 1));
                        retval.add(sql.toString());
                        sql.setLength(0);
                    } else {
                        sql.append(" ");
                        sql.append(line);
                    }
                }
            }

            if (sql.length() > 0) {
                retval.add(sql.toString());
            }
        } catch (Exception ex) {
            writeOut(ex);
        } finally {
            try {
                if (lnr != null) {
                    lnr.close();
                }
            } catch (Exception ex) {
            }
        }

        return retval;
    }

    private File getUpgradeFile(String fname) {
        File retval = null;

        int pos = fname.lastIndexOf(".");

        File modFile = new File(fname.substring(0, pos) + "_mod" + fname.substring(pos));

        if (modFile.isFile() && modFile.exists()) {
            retval = modFile;
        } else {
            retval = new File(fname);
        }

        return retval;
    }

    private String getLastProcessedFolder(String lastProcessedFile) {
        String retval = null;
        String s = lastProcessedFile.substring(properties.getProperty("upgrade-base-directory").length() + 1);
        int pos = s.indexOf("/");
        retval = s.substring(0, pos);
        return retval;
    }

    private List<String> getFolders(String lastProcessedFile) {
        List<String> retval = new ArrayList<String>();

        if (StringUtils.isNotBlank(lastProcessedFile)) {
            String lastProcessedFolder = getLastProcessedFolder(lastProcessedFile);
            boolean foundit = false;
            for (String folder : upgradeFolders) {
                if (lastProcessedFolder.equals(folder)) {
                    foundit = true;
                }

                if (foundit) {
                    retval.add(folder);
                }
            }
        } else {
            retval = upgradeFolders;
        }

        return retval;
    }

    private List<String> getFolderFiles(String folder, String lastProcessedFile) {
        List<String> retval = new ArrayList<String>();
        if (StringUtils.isBlank(lastProcessedFile)) {
            retval = upgradeFiles.get(folder);
        } else {
            boolean foundit = false;
            String lastProcessedFolder = getLastProcessedFolder(lastProcessedFile);
            int len = (properties.getProperty("upgrade-base-directory") + "/" + folder + "/").length();

            if (!folder.equals(lastProcessedFolder)) {
                retval = upgradeFiles.get(folder);
            } else {
                for (String s : upgradeFiles.get(folder)) {
                    if (s.equals(lastProcessedFile.substring(len))) {
                        foundit = true;
                    } else if (foundit) {
                        retval.add(s);
                    }
                }
            }
        }
        return retval;
    }

    private boolean doUpgrade(Connection conn1, Connection conn2, Statement stmt) {
        boolean retval = true;
        writeHeader1("upgrading kfs");

        String lastProcessedFile = properties.getProperty("last-processed-file");

        List<String> folders = getFolders(lastProcessedFile);

        for (String folder : folders) {
            writeHeader2("processing folder " + folder);

            List<String> folderFiles = getFolderFiles(folder, lastProcessedFile);
            if (folderFiles != null) {
                for (String fname : folderFiles) {
                    if (isMethodCall(fname)) {
                        retval = callMethod(fname, conn1, stmt);
                    } else {
                        File f = getUpgradeFile(upgradeRoot + "/" + folder + "/" + fname);

                        if (f.getName().endsWith(".sql")) {
                            if (!runSqlFile(conn1, stmt, f, ";")) {
                                retval = false;
                                writeProcessedFileInfo("[failure] " + f.getPath());
                            } else {
                                writeProcessedFileInfo("[success] " + f.getPath());
                            }
                        } else {
                            if (!runLiquibase(conn2, f)) {
                                retval = false;
                                writeProcessedFileInfo("[failure] " + f.getPath());
                            } else {
                                writeProcessedFileInfo("[success] " + f.getPath());
                            }
                        }
                    }

                    if (!retval) {
                        break;
                    }
                }
            } else {
                retval = true;
            }

            if (!retval) {
                break;
            }
        }

        return retval;
    }

    private boolean runLiquibase(Connection conn, File f) {
        boolean retval = true;
        writeHeader2("processing liquibase file " + f.getPath());
        PrintWriter pw = null;
        try {
            Liquibase liquibase = new Liquibase(f.getName(), new FileSystemFileOpener(f.getParentFile().getPath()), conn);
            liquibase.getDatabase().setAutoCommit(true);
            liquibase.reportStatus(true, null, pw = getOutputLogWriter());
            liquibase.update(null);
            retval = true;
        } catch (Exception ex) {
            retval = false;
            if (pw != null) {
                pw.close();
            }
            pw = null;
            writeOut(ex);
        } finally {
            if (pw != null) {
                pw.close();
            }
        }

        return retval;
    }

    public void writeOut(Exception ex) {
        System.out.println();
        System.out.println(getTimeString() + ERROR);
        ex.printStackTrace(System.out);
        writeLog(ex);
    }

    private void writeLog(Exception ex) {
        PrintWriter pw = null;

        try {
            pw = getOutputLogWriter();
            pw.println();
            pw.println(getTimeString() + ERROR);
            ex.printStackTrace(pw);
        } catch (Exception ex2) {
        } finally {
            try {
                if (pw != null) {
                    pw.close();
                }
            } catch (Exception ex2) {
            };
        }
    }

    public void writeOut(String msg) {
        System.out.println(msg);
        writeLog(msg);
    }

    private void writeLog(String msg) {
        PrintWriter pw = null;

        try {
            pw = getOutputLogWriter();
            if (StringUtils.isNotBlank(msg)) {
                pw.println(getTimeString() + msg);
            } else {
                pw.println();
            }
        } catch (Exception ex2) {
        } finally {
            try {
                if (pw != null) {
                    pw.close();
                }
            } catch (Exception ex2) {
            };
        }
    }

    private String getTimeString() {
        return "[" + DF.format(new Date()) + "] ";
    }

    private Connection getUpgradeConnection() throws Exception {
        Connection retval = null;
        String url = properties.getProperty("database-url");

        Properties props = new Properties();
        props.setProperty("user", properties.getProperty("database-user"));
        props.setProperty("password", properties.getProperty("database-password"));

        writeOut("Connecting to db " + properties.getProperty("database-name") + "...");
        writeOut("url=" + url);

        Class.forName(properties.getProperty("database-driver"));
        retval = DriverManager.getConnection(url, props);
        retval.setReadOnly(false);
        retval.setAutoCommit(false);

        writeOut("connected to database " + properties.getProperty("database-name"));
        writeOut("");

        return retval;
    }

    private Connection getLegacyConnection() throws Exception {
        Connection retval = null;
        String url = properties.getProperty("legacy-database-url");

        Properties props = new Properties();
        props.setProperty("user", properties.getProperty("legacy-database-user"));
        props.setProperty("password", properties.getProperty("legacy-database-password"));

        writeOut("Connecting to db " + properties.getProperty("legacy-database-name") + "...");
        writeOut("url=" + url);

        Class.forName(properties.getProperty("legacy-database-driver"));
        retval = DriverManager.getConnection(url, props);
        retval.setReadOnly(false);
        retval.setAutoCommit(false);

        writeOut("connected to database " + properties.getProperty("legacy-database-name"));
        writeOut("");

        return retval;

    }

    private void closeDbObjects(Connection conn, Statement stmt, ResultSet res) {
        try {
            if (res != null) {
                res.close();
            }
        } catch (Exception ex) {
        };
        try {
            if (stmt != null) {
                stmt.close();
            }
        } catch (Exception ex) {
        };
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (Exception ex) {
        };
    }

    private boolean executeSql(Connection conn, Statement stmt, String sql) {
        boolean retval = false;

        try {
            if (isDDL(sql)) {
                stmt.execute(sql);
            } else {
                stmt.executeUpdate(sql);
            }

            retval = true;
        } catch (Exception ex) {
            writeOut(ex);
        }

        return retval;
    }

    private boolean isDDL(String sql) {
        String s = sql.toUpperCase();
        return (!s.toUpperCase().startsWith("UPDATE") && !s.startsWith("INSERT") && !s.startsWith("DELETE"));
    }

    private void deleteFile(File f) throws IOException {
        try {
            FileUtils.forceDelete(f);
        } catch (FileNotFoundException ex) {
        };
    }

    private boolean doInitialProcessing(Connection conn, Statement stmt) {
        boolean retval = false;
        ResultSet res = null;
        ResultSet res2 = null;
        PreparedStatement stmt2 = null;
        try {
            deleteFile(new File(properties.getProperty("output-log-file-name")));
            deleteFile(new File(properties.getProperty("processed-files-file-name")));

            writeHeader1("pre-upgrade processing");
            writeHeader2("dropping materialized view logs...");
            res = stmt.executeQuery("select LOG_OWNER || '.' || MASTER from SYS.user_mview_logs");

            List<String> logs = new ArrayList<String>();

            while (res.next()) {
                logs.add(res.getString(1));
            }

            for (String log : logs) {
                stmt.execute("drop materialized view log on " + log);
                writeOut("dropped materialized view log on " + log);
            }

            res.close();

            writeHeader2("ensuring combination of (SORT_CD, KIM_TYP_ID, KIM_ATTR_DEFN_ID, ACTV_IND) unique on KRIM_TYP_ATTR_T...");

            StringBuilder sql = new StringBuilder(256);
            sql.append("select count(*), SORT_CD, KIM_TYP_ID, KIM_ATTR_DEFN_ID, ACTV_IND ");
            sql.append("from KRIM_TYP_ATTR_T group by SORT_CD, KIM_TYP_ID, KIM_ATTR_DEFN_ID, ACTV_IND ");
            sql.append("having count(*) > 1");
            res = stmt.executeQuery(sql.toString());

            while (res.next()) {
                if (stmt2 == null) {
                    stmt2 = conn.prepareStatement("select KIM_TYP_ATTR_ID from KRIM_TYP_ATTR_T where sort_cd = ? and KIM_TYP_ID = ? and  KIM_ATTR_DEFN_ID = ? and ACTV_IND = ?");
                }
                String sortCd = res.getString(1);
                stmt2.setString(1, sortCd);
                stmt2.setString(2, res.getString(2));
                stmt2.setString(3, res.getString(3));
                stmt2.setString(4, res.getString(4));

                res2 = stmt2.executeQuery();

                int indx = 0;
                while (res2.next()) {
                    if (indx > 0) {
                        indx++;
                        if (sortCd.length() == 1) {
                            stmt.executeUpdate("update KRIM_TYP_ATTR_T set sort_cd = '" + (sortCd + indx) + "' where KIM_TYP_ATTR_ID = '" + res2.getString(1) + "'");
                        }
                    }
                }
            }

            retval = true;
        } catch (Exception ex) {
            writeOut(ex);
        } finally {
            closeDbObjects(null, null, res);
            closeDbObjects(null, stmt2, res2);
        }

        return retval;
    }

    private void writeHeader1(String msg) {
        writeOut("");
        writeOut(HEADER1.replace("?", msg));
        writeOut("");
    }

    private void writeHeader2(String msg) {
        writeOut("");
        writeOut(msg);
        writeOut(UNDERLINE);
        writeOut("");
    }

    private PrintWriter getOutputLogWriter() throws IOException {
        return new PrintWriter(new FileWriter(properties.getProperty("output-log-file-name"), true));
    }

    private PrintWriter getProcessedFilesWriter() throws IOException {
        return new PrintWriter(new FileWriter(properties.getProperty("processed-files-file-name"), true));
    }

    private void writeProcessedFileInfo(String txt) {
        PrintWriter pw = null;
        try {
            pw = getProcessedFilesWriter();
            pw.println(txt);
        } catch (Exception ex) {
            writeOut(ex);
        } finally {
            try {
                if (pw != null) {
                    pw.close();
                }
            } catch (Exception ex) {
            };
        }
    }

    private String getIndexTableName(String line) {
        String retval = null;
        int pos = line.indexOf(" ON ");

        if (pos > -1) {
            pos = line.indexOf(".", pos);
            int pos2 = line.indexOf("(", pos);

            if ((pos > -1) && (pos2 > -1) && (pos2 > pos)) {
                retval = line.substring(pos + 1, pos2);
            }
        }

        return retval;
    }

    private String getIndexName(String line) {
        String retval = null;
        StringTokenizer st = new StringTokenizer(line);
        if (st.countTokens() > 4) {
            int cnt = 2;
            if (line.contains(" UNIQUE ")) {
                cnt = 3;
            }

            for (int i = 0; i < cnt; ++i) {
                st.nextToken();
            }

            retval = st.nextToken().substring("KULOWNER.".length());
        }
        return retval;
    }

    private List<String> getIndexColumnNames(String line) {
        List<String> retval = new ArrayList<String>();

        int pos = line.indexOf("(");
        int pos2 = line.indexOf(")");

        if ((pos > -1) && (pos2 > -1) && (pos2 > pos)) {
            StringTokenizer st = new StringTokenizer(line.substring(pos + 1, pos2), ",");

            while (st.hasMoreTokens()) {
                retval.add(st.nextToken());
            }
        }

        return retval;
    }

    private void createExistingIndexes(Connection conn, Statement stmt) {
        LineNumberReader lnr = null;

        writeHeader2("creating KFS indexes that existed prior to upgrade where required ");

        try {
            lnr = new LineNumberReader(new FileReader(upgradeRoot + "/post-upgrade/sql/kfs-indexes.sql"));

            String line = null;

            while ((line = lnr.readLine()) != null) {
                String tableName = getIndexTableName(line);
                String indexName = getIndexName(line);
                if (StringUtils.isNotBlank(tableName) && StringUtils.isNotBlank(indexName)) {
                    if (tableExists(conn, stmt, tableName)) {
                        boolean unique = line.contains(" UNIQUE ");
                        List<String> columnNames = getIndexColumnNames(line);

                        if (!indexExists(conn, stmt, tableName, columnNames)) {
                            if (indexNameExists(conn, stmt, tableName, indexName)) {
                                indexName = getNextTableIndexName(conn, stmt, tableName);
                            }

                            StringBuilder sql = new StringBuilder(256);

                            sql.append("CREATE ");

                            if (unique) {
                                sql.append("UNIQUE ");
                            }

                            sql.append("INDEX KULOWNER.");

                            sql.append(indexName);
                            sql.append(" ON KULOWNER.");
                            sql.append(tableName);
                            sql.append("(");

                            String comma = "";
                            for (String columnName : columnNames) {
                                sql.append(comma);
                                sql.append(columnName);
                                comma = ",";
                            }

                            sql.append(")");

                            try {
                                stmt.execute(sql.toString());
                            } catch (SQLException ex) {
                                writeOut("failed to create index: " + sql.toString());
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            writeOut(ex);
        } finally {
            try {
                if (lnr != null) {
                    lnr.close();
                }
            } catch (Exception ex) {
            };
        }
    }

    private boolean tableExists(Connection conn, Statement stmt, String tableName) throws Exception {
        boolean retval = false;
        ResultSet res = null;

        try {
            res = stmt.executeQuery("select count(*) from user_tables where table_name = '" + tableName + "'");

            if (res.next()) {
                retval = (res.getInt(1) > 0);
            }
        } finally {
            closeDbObjects(null, null, res);
        }

        return retval;

    }

    private boolean indexExists(Connection conn, Statement stmt, String tableName, List<String> columnNames) throws Exception {
        boolean retval = false;
        ResultSet res = null;

        try {
            StringBuilder sql = new StringBuilder(256);

            sql.append("select index_name, column_name, column_position ");
            sql.append("from all_ind_columns ");
            sql.append("where index_owner = 'KULOWNER' ");
            sql.append("and table_owner = 'KULOWNER' ");
            sql.append("and table_name = '");
            sql.append(tableName);
            sql.append("' order by index_name, column_position");

            Map<String, List<String>> map = new HashMap<String, List<String>>();

            res = stmt.executeQuery(sql.toString());
            while (res.next()) {
                String indexName = res.getString(1);

                List<String> columns = map.get(indexName);

                if (columns == null) {
                    map.put(indexName, columns = new ArrayList<String>());
                }

                columns.add(res.getString(2));
            }

            for (List<String> columns : map.values()) {
                if (columns.size() == columnNames.size()) {
                    boolean foundit = true;
                    for (int i = 0; i < columns.size(); ++i) {
                        if (!columns.get(i).equals(columnNames.get(i))) {
                            foundit = false;
                            break;
                        }
                    }

                    if (foundit) {
                        retval = true;
                        break;
                    }
                }
            }
        } finally {
            closeDbObjects(null, null, res);
        }

        return retval;
    }

    private boolean indexNameExists(Connection conn, Statement stmt, String tableName, String indexName) throws Exception {
        boolean retval = false;
        ResultSet res = null;

        try {
            res = stmt.executeQuery("select count(*) from user_indexes where table_owner = 'KULOWNER' and table_name = '" + tableName + "' and index_name = '" + indexName + "'");
            if (res.next()) {
                retval = (res.getInt(1) > 0);
            }
        } finally {
            closeDbObjects(null, null, res);
        }

        return retval;
    }

    private String getNextTableIndexName(Connection conn, Statement stmt, String tableName) throws Exception {
        String retval = null;

        int maxIndex = -1;
        ResultSet res = null;

        try {
            res = stmt.executeQuery("select index_name from user_indexes where table_owner = 'KULOWNER' and table_name = '" + tableName + "'");
            while (res.next()) {
                String indexName = res.getString(1);
                int pos = indexName.lastIndexOf("_");

                if (pos > -1) {
                    if (indexName.substring(pos + 1).startsWith("TI")) {
                        try {
                            int i = Integer.parseInt(indexName.substring(pos + 3));
                            if (i > maxIndex) {
                                maxIndex = i;

                            }
                        } catch (NumberFormatException ex) {
                        };
                    }
                }
            }

            retval = (tableName + "I" + (maxIndex + 1));
        } finally {
            closeDbObjects(null, null, res);
        }

        return retval;
    }

    private String getSynonymName(String line) {
        String retval = null;
        StringTokenizer st = new StringTokenizer(line);

        st.nextToken();
        st.nextToken();
        st.nextToken();
        String token = st.nextToken();

        int pos = token.lastIndexOf(";");

        if (pos > -1) {
            retval = token.substring(0, pos).trim();
        } else {
            retval = token.trim();
        }

        return retval;
    }

    private boolean synonymExists(Connection conn, Statement stmt, String synonymName) throws Exception {
        boolean retval = false;
        ResultSet res = null;

        try {
            res = stmt.executeQuery("select count(*) from all_synonyms where owner = 'PUBLIC' and synonym_name = '" + synonymName + "'");
            if (res.next()) {
                retval = (res.getInt(1) > 0);
            }
        } finally {
            closeDbObjects(null, null, res);
        }

        return retval;
    }

    private void createPublicSynonyms(Connection conn, Statement stmt) {
        LineNumberReader lnr = null;

        writeHeader2("creating KFS public synonyms that existed prior to upgrade where required ");

        try {
            lnr = new LineNumberReader(new FileReader(upgradeRoot + "/post-upgrade/sql/kfs-public-synonyms.sql"));

            String line = null;

            while ((line = lnr.readLine()) != null) {
                String synonymName = getSynonymName(line);

                if (!synonymExists(conn, stmt, synonymName)) {
                    try {
                        stmt.execute(line);
                    } catch (SQLException ex) {
                        writeOut("failed to create public synonym: " + line);
                    }
                }
            }
        } catch (Exception ex) {
            writeOut(ex);
        } finally {
            try {
                if (lnr != null) {
                    lnr.close();
                }
            } catch (Exception ex) {
            };
        }
    }

    private Set<String> loadForeignKeyIndexInformation(DatabaseMetaData dmd, String table) {
        Set<String> retval = new HashSet<String>();

        ResultSet res = null;
        try {
            Map<String, ForeignKeyReference> fkeys = new HashMap<String, ForeignKeyReference>();
            Map<String, TableIndexInfo> tindexes = new HashMap<String, TableIndexInfo>();
            res = dmd.getImportedKeys(null, getSchema(), table);
            boolean foundfk = false;

            while (res.next()) {
                foundfk = true;
                String fcname = res.getString(8);
                int seq = res.getInt(9);
                String fkname = res.getString(12);

                ForeignKeyReference fkref = fkeys.get(fkname);

                if (fkref == null) {
                    fkeys.put(fkname, fkref = new ForeignKeyReference(getSchema(), table, fkname, INDEX_NAME_TEMPLATE));
                }

                ColumnInfo cinfo = new ColumnInfo(fcname, seq);

                cinfo.setNumeric(isNumericColumn(dmd, getSchema(), table, fcname));

                fkref.addColumn(cinfo);
            }

            res.close();

            if (foundfk) {
                tindexes.put(table, loadTableIndexInfo(dmd, table));
            }

            List<ForeignKeyReference> l = new ArrayList<ForeignKeyReference>(fkeys.values());

            Collections.sort(l);

            Iterator<ForeignKeyReference> it = l.iterator();

            while (it.hasNext()) {
                ForeignKeyReference fkref = it.next();
                if (hasIndex(tindexes.get(fkref.getTableName()).getIndexes(), fkref)) {
                    it.remove();
                } else {
                    String s = fkref.getCreateIndexString(tindexes.get(fkref.getTableName()));
                    if (StringUtils.isNotBlank(fkref.getIndexName())) {
                        retval.add(s);
                    }
                }
            }
        } catch (Exception ex) {
            writeOut(ex);
        } finally {
            closeDbObjects(null, null, res);
        }

        return retval;
    }

    private TableIndexInfo loadTableIndexInfo(DatabaseMetaData dmd, String tname) throws Exception {
        TableIndexInfo retval = new TableIndexInfo(tname);
        ResultSet res = null;

        try {
            Map<String, IndexInfo> imap = new HashMap<String, IndexInfo>();

            res = dmd.getIndexInfo(null, getSchema(), tname, false, true);

            while (res.next()) {
                String iname = res.getString(6);

                if (iname != null) {
                    String cname = res.getString(9);

                    IndexInfo i = imap.get(iname);

                    if (i == null) {
                        imap.put(iname, i = new IndexInfo(iname));
                    }

                    i.addColumn(cname);
                }
            }

            retval.getIndexes().addAll(imap.values());

            for (IndexInfo i : retval.getIndexes()) {
                String indexName = i.getIndexName();

                int indx = 1;
                for (int j = indexName.length() - 1; j >= 0; --j) {
                    if (!Character.isDigit(indexName.charAt(j))) {
                        try {
                            indx = Integer.parseInt(indexName.substring(j + 1));
                        } catch (NumberFormatException ex) {
                        };

                        break;
                    }
                }

                if (retval.getMaxIndexSuffix() < indx) {
                    retval.setMaxIndexSuffix(indx);
                }
            }
        } finally {
            closeDbObjects(null, null, res);
        }

        return retval;
    }

    private boolean hasIndex(List<IndexInfo> indexes, ForeignKeyReference fkref) throws Exception {
        boolean retval = false;

        for (IndexInfo i : indexes) {
            if (fkref.getColumns().size() == i.getIndexColumns().size()) {
                boolean foundit = true;
                for (ColumnInfo cinfo : fkref.getColumns()) {
                    if (!i.getIndexColumns().contains(cinfo.getColumnName())) {
                        foundit = false;
                    }
                }

                if (foundit) {
                    retval = true;
                    break;
                }
            }
        }

        return retval;
    }

    private String getSchema() {
        return properties.getProperty("database-schema");
    }

    private boolean isNumericColumn(DatabaseMetaData dmd, String schema, String tname, String cname) throws Exception {
        boolean retval = false;

        ResultSet res = null;

        try {
            res = dmd.getColumns(null, schema, tname, cname);

            if (res.next()) {
                retval = isNumericJavaType(res.getInt(5));

            }
        } finally {
            closeDbObjects(null, null, res);
        }

        return retval;
    }

    private boolean isNumericJavaType(int type) {
        return ((type == java.sql.Types.BIGINT)
            || (type == java.sql.Types.BINARY)
            || (type == java.sql.Types.DECIMAL)
            || (type == java.sql.Types.DOUBLE)
            || (type == java.sql.Types.FLOAT)
            || (type == java.sql.Types.INTEGER)
            || (type == java.sql.Types.NUMERIC)
            || (type == java.sql.Types.REAL)
            || (type == java.sql.Types.SMALLINT)
            || (type == java.sql.Types.TINYINT));
    }

    private void createForeignKeyIndexes(Connection conn, Statement stmt) {
        writeHeader2("creating indexes on foreign keys where required...");
        ResultSet res = null;
        try {
            DatabaseMetaData dmd = conn.getMetaData();
            res = dmd.getTables(null, getSchema(), null, new String[]{"TABLE"});

            while (res.next()) {
                String tname = res.getString(3);

                Set<String> sqllist = loadForeignKeyIndexInformation(dmd, tname);

                if ((sqllist != null) && !sqllist.isEmpty()) {
                    writeOut("creating required foreign key indexes on table " + tname + "...");
                    int cnt = 0;
                    for (String sql : sqllist) {
                        try {
                            stmt.executeQuery(sql);
                            cnt++;
                        } catch (Exception ex) {
                            writeOut("create index failed: " + sql);
                        }
                    }

                    writeOut("    " + cnt + " indexes created");
                }
            }
        } catch (Exception ex) {
            writeOut(ex);
        } finally {
            closeDbObjects(null, null, res);
        }
    }

    private void createDocumentSearchEntries(Connection conn, Statement stmt) {
        PreparedStatement pstmt = null;
        ResultSet res = null;
        try {
            conn.setAutoCommit(false);
            stmt = conn.createStatement();

            pstmt = conn.prepareStatement("insert into krew_doc_hdr_ext_t (doc_hdr_ext_id, doc_hdr_id, key_cd, val) values (to_char(KREW_SRCH_ATTR_S.nextval), ?, 'displayType', 'document')");

            int i = 1;
            res = stmt.executeQuery("select distinct doc_hdr_id from krew_doc_hdr_ext_t order by  1");

            while (res.next()) {
                pstmt.setString(1, res.getString(1));
                pstmt.executeUpdate();

                if ((i % 10000) == 0) {
                    writeOut(i + " krew_doc_hdr_ext_t entries inserted");
                }

                i++;
            }

            conn.commit();
        } catch (Exception ex) {
            ex.printStackTrace();

            if (conn != null) {
                try {
                    conn.rollback();
                } catch (Exception ex2) {
                };
            }
        } finally {
            try {
                closeDbObjects(null, pstmt, res);
            } catch (Exception ex) {
            };
        }
    }

    private void runMiscSql(Connection conn, Statement stmt) {
        LineNumberReader lnr = null;

        writeHeader2("Executiong miscellaneous post-upgrade sql");
        try {
            lnr = new LineNumberReader(new FileReader(upgradeRoot + "/post-upgrade/sql/misc.sql"));

            String sql = null;

            while ((sql = lnr.readLine()) != null) {
                if (StringUtils.isNotBlank(sql)) {
                    try {
                        if (sql.trim().endsWith(";")) {
                            int pos = sql.lastIndexOf(";");
                            sql = sql.substring(0, pos);
                        }
                    
                        if (isDDL(sql)) {
                            stmt.execute(sql);
                        } else {
                            stmt.executeUpdate(sql);
                        }
                        writeOut(sql);
                    } catch (SQLException ex) {
                        writeOut("sql execution failed: " + sql);
                    }
                }
            }
        } catch (Exception ex) {
            writeOut(ex);
        } finally {
            try {
                if (lnr != null) {
                    lnr.close();
                }
            } catch (Exception ex) {
            };
        }
    }

    private void updatePurchasingStatuses(Connection upgradeConn) {
        Connection legacyConn = null;
        Statement legacyStmt = null;
        ResultSet legacyRes = null;
        PreparedStatement upgradeStmt1 = null;
        PreparedStatement upgradeStmt2 = null;
        try {
            // load status names from legacy status tables
            legacyConn = getLegacyConnection();
            legacyStmt = legacyConn.createStatement();

            legacyRes = legacyStmt.executeQuery("select CRDT_MEMO_STAT_CD, CRDT_MEMO_STAT_DESC from AP_CRDT_MEMO_STAT_T");

            upgradeStmt1 = upgradeConn.prepareStatement("update krew_doc_hdr_t set app_doc_stat = ? where doc_hdr_id in (select fdoc_nbr from AP_CRDT_MEMO_T where DEPR_CRDT_MEMO_STAT_CD = ?)");
            upgradeStmt2 = upgradeConn.prepareStatement("update fs_doc_header_t set app_doc_stat = ? where fdoc_nbr in (select fdoc_nbr from AP_CRDT_MEMO_T where DEPR_CRDT_MEMO_STAT_CD = ?)");
            while (legacyRes.next()) {
                String cd = legacyRes.getString(1);
                String desc = legacyRes.getString(2);

                writeOut("updating credit memo app_doc_stat[" + desc + "] in krew_doc_hdr_t...");
                upgradeStmt1.setString(1, desc.replace("&", "and"));
                upgradeStmt1.setString(2, cd);
                upgradeStmt1.executeUpdate();

                writeOut("updating credit memo app_doc_stat[" + desc + "]  in fs_doc_header_t...");
                upgradeStmt2.setString(1, desc.replace("&", "and"));
                upgradeStmt2.setString(2, cd);
                upgradeStmt2.executeUpdate();
            }

            closeDbObjects(null, upgradeStmt1, legacyRes);
            closeDbObjects(null, upgradeStmt2, null);

            legacyRes = legacyStmt.executeQuery("select PMT_RQST_STAT_CD, PMT_RQST_STAT_DESC from AP_PMT_RQST_STAT_T");
            upgradeStmt1 = upgradeConn.prepareStatement("update krew_doc_hdr_t set app_doc_stat = ? where doc_hdr_id in (select fdoc_nbr from AP_PMT_RQST_T where DEPR_PMT_RQST_STAT_CD = ?)");
            upgradeStmt2 = upgradeConn.prepareStatement("update fs_doc_header_t set app_doc_stat = ? where fdoc_nbr in (select fdoc_nbr from AP_PMT_RQST_T where DEPR_PMT_RQST_STAT_CD = ?)");
            while (legacyRes.next()) {
                String cd = legacyRes.getString(1);
                String desc = legacyRes.getString(2);

                writeOut("updating payment request app_doc_stat[" + desc + "]  in krew_doc_hdr_t...");
                upgradeStmt1.setString(1, desc.replace("&", "and"));
                upgradeStmt1.setString(2, cd);
                upgradeStmt1.executeUpdate();

                writeOut("updating payment request app_doc_stat[" + desc + "]  in fs_doc_header_t...");
                upgradeStmt2.setString(1, desc.replace("&", "and"));
                upgradeStmt2.setString(2, cd);
                upgradeStmt2.executeUpdate();
            }

            closeDbObjects(null, upgradeStmt1, legacyRes);
            closeDbObjects(null, upgradeStmt2, null);

            legacyRes = legacyStmt.executeQuery("select PO_STAT_CD, PO_STAT_DESC from PUR_PO_STAT_T");
            upgradeStmt1 = upgradeConn.prepareStatement("update krew_doc_hdr_t set app_doc_stat = ? where doc_hdr_id in (select fdoc_nbr from PUR_PO_T where DEPR_PO_STAT_CD = ?)");
            upgradeStmt2 = upgradeConn.prepareStatement("update fs_doc_header_t set app_doc_stat = ? where fdoc_nbr in (select fdoc_nbr from PUR_PO_T where DEPR_PO_STAT_CD = ?)");
            while (legacyRes.next()) {
                String cd = legacyRes.getString(1);
                String desc = legacyRes.getString(2);

                writeOut("updating purchase order app_doc_stat[" + desc + "]  in krew_doc_hdr_t...");
                upgradeStmt1.setString(1, desc.replace("&", "and"));
                upgradeStmt1.setString(2, cd);
                upgradeStmt1.executeUpdate();

                writeOut("updating purchase order app_doc_stat[" + desc + "]  in fs_doc_header_t...");
                upgradeStmt2.setString(1, desc.replace("&", "and"));
                upgradeStmt2.setString(2, cd);
                upgradeStmt2.executeUpdate();
            }

            closeDbObjects(null, upgradeStmt1, legacyRes);
            closeDbObjects(null, upgradeStmt2, null);

            legacyRes = legacyStmt.executeQuery("select RCVNG_LN_STAT_CD, RCVNG_LN_STAT_DESC from PUR_RCVNG_LN_STAT_T");
            upgradeStmt1 = upgradeConn.prepareStatement("update krew_doc_hdr_t set app_doc_stat = ? where doc_hdr_id in (select fdoc_nbr from PUR_RCVNG_LN_T where DEPR_RCVNG_LN_STAT_CD = ?)");
            upgradeStmt2 = upgradeConn.prepareStatement("update fs_doc_header_t set app_doc_stat = ? where fdoc_nbr in (select fdoc_nbr from PUR_RCVNG_LN_T where DEPR_RCVNG_LN_STAT_CD = ?)");
            while (legacyRes.next()) {
                String cd = legacyRes.getString(1);
                String desc = legacyRes.getString(2);

                writeOut("updating purchase receiving line app_doc_stat[" + desc + "]  in krew_doc_hdr_t...");
                upgradeStmt1.setString(1, desc.replace("&", "and"));
                upgradeStmt1.setString(2, cd);
                upgradeStmt1.executeUpdate();

                writeOut("updating purchase receiving line app_doc_stat[" + desc + "]  in fs_doc_header_t...");
                upgradeStmt2.setString(1, desc.replace("&", "and"));
                upgradeStmt2.setString(2, cd);
                upgradeStmt2.executeUpdate();
            }

            closeDbObjects(null, upgradeStmt1, legacyRes);
            closeDbObjects(null, upgradeStmt2, null);

            legacyRes = legacyStmt.executeQuery("select REQS_STAT_CD, REQS_STAT_DESC from PUR_REQS_STAT_T");
            upgradeStmt1 = upgradeConn.prepareStatement("update krew_doc_hdr_t set app_doc_stat = ? where doc_hdr_id in (select fdoc_nbr from PUR_REQS_T where DEPR_REQS_STAT_CD = ?)");
            upgradeStmt2 = upgradeConn.prepareStatement("update fs_doc_header_t set app_doc_stat = ? where fdoc_nbr in (select fdoc_nbr from PUR_REQS_T where DEPR_REQS_STAT_CD = ?)");
            while (legacyRes.next()) {
                String cd = legacyRes.getString(1);
                String desc = legacyRes.getString(2);

                writeOut("updating requisition app_doc_stat[" + desc + "]  in krew_doc_hdr_t...");
                upgradeStmt1.setString(1, desc.replace("&", "and"));
                upgradeStmt1.setString(2, cd);
                upgradeStmt1.executeUpdate();

                writeOut("updating requisition app_doc_stat[" + desc + "]  in fs_doc_header_t...");
                upgradeStmt2.setString(1, desc.replace("&", "and"));
                upgradeStmt2.setString(2, cd);
                upgradeStmt2.executeUpdate();
            }

            upgradeConn.commit();
        } catch (Exception ex) {
            writeOut(ex);
            try {
                upgradeConn.rollback();
            } catch (Exception ex2) {
            };
        } finally {
            closeDbObjects(legacyConn, legacyStmt, legacyRes);
            closeDbObjects(null, upgradeStmt1, null);
            closeDbObjects(null, upgradeStmt2, null);
        }
    }

    private boolean ensureNmNmspccdUnique(Connection conn, Statement stmt) {
        boolean retval = false;
        ResultSet res = null;
        try {
            List<String> updates = new ArrayList<String>();

            writeHeader2("ensuring combination of (NM, NMSPC_CD) unique on KRIM_PERM_T and  KRIM_RSP_T...");

            // find duplicates
            res = stmt.executeQuery("select count(*), NM, NMSPC_CD from KRIM_PERM_T group by NM, NMSPC_CD having count(*) > 1");

            //tack perm_id to name to make unique
            while (res.next()) {
                String nm = res.getString(2);
                String nmspccd = res.getString(3);
                updates.add("update KRIM_PERM_T set nm = nm || '[' || perm_id || ']' where nm = '" + nm + "' and nmspc_cd = '" + nmspccd + "'");
            }

            res.close();

            // find duplicates
            res = stmt.executeQuery("select count(*), NM, NMSPC_CD from  KRIM_RSP_T group by NM, NMSPC_CD having count(*) > 1");

            //tack rsp_id to name to make unique
            while (res.next()) {
                String nm = res.getString(2);
                String nmspccd = res.getString(3);
                updates.add("update KRIM_RSP_T set nm = nm || '[' || rsp_id || ']' where nm = '" + nm + "' and nmspc_cd = '" + nmspccd + "'");
            }

            for (String sql : updates) {
                writeOut("executing: " + sql);
                stmt.executeUpdate(sql);
            }

            res.close();
            retval = true;
        } catch (Exception ex) {
            writeOut(ex);
        } finally {
            closeDbObjects(null, null, res);
        }

        return retval;
    }

    private boolean isMethodCall(String nm) {
        return (StringUtils.isNotBlank(nm) && nm.contains("method:"));
    }

    private boolean callMethod(String nm, Connection conn, Statement stmt) {
        boolean retval = false;
        if (StringUtils.isNotBlank(nm)) {
            if (nm.contains("ensureNmNmspccdUnique")) {
                retval = ensureNmNmspccdUnique(conn, stmt);
            }

            if (retval) {
                doCommit(conn);
            } else {
                doRollback(conn);
            }
        }

        return retval;
    }
    
    private void populateProcurementCardTable(Connection conn) {
        Statement stmt = null;
        PreparedStatement pstmt = null;
        ResultSet res = null;
        writeHeader2("Populating procurement card default table with UA detail data");
        
        try {
            StringBuilder sql = new StringBuilder(512);
            
            sql.append("insert into fp_prcrmnt_card_dflt_t (");
            sql.append("ID,"); // 1
            sql.append("CC_NBR,"); // 2
            sql.append("VER_NBR,"); // 3
            sql.append("OBJ_ID,"); // 4
            sql.append("CARD_HLDR_NM,"); // 5
            sql.append("CARD_HLDR_ALTRNT_NM,"); // 6
            sql.append("CARD_HLDR_LN1_ADDR,"); // 7
            sql.append("CARD_HLDR_LN2_ADDR,"); // 8
            sql.append("CARD_HLDR_CTY_NM,"); // 9
            sql.append("CARD_HLDR_ST_CD,"); // 10
            sql.append("CARD_HLDR_ZIP_CD,"); // 11
            sql.append("CARD_HLDR_WRK_PHN_NBR,"); // 12
            sql.append("CARD_LMT,"); // 13
            sql.append("CARD_CYCLE_AMT_LMT,"); // 14
            sql.append("CARD_CYCLE_VOL_LMT,"); // 15
            sql.append("CARD_STAT_CD,"); // 16
            sql.append("CARD_NTE_TXT,"); // 17
            sql.append("FIN_COA_CD,"); // 18
            sql.append("ACCOUNT_NBR,"); // 19
            sql.append("SUB_ACCT_NBR,"); // 20
            sql.append("FIN_OBJECT_CD,"); // 21
            sql.append("FIN_SUB_OBJ_CD,"); // 22
            sql.append("PROJECT_CD,"); // 23
            sql.append("ROW_ACTV_IND"); // 24
            sql.append(") values (FP_PRCRMNT_CARD_DFLT_SEQ.nextVal, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

            pstmt = conn.prepareStatement(sql.toString());
            stmt = conn.createStatement();
            
            sql.setLength(0);
            sql.append("select ");
            sql.append("CC_NBR,"); // 2
            sql.append("VER_NBR,"); // 3
            sql.append("OBJ_ID,"); // 4
            sql.append("CARD_HLDR_NM,"); // 5
            sql.append("CARD_HLDR_ALTRNT_NM,"); // 6
            sql.append("CARD_HLDR_LN1_ADDR,"); // 7
            sql.append("CARD_HLDR_LN2_ADDR,"); // 8
            sql.append("CARD_HLDR_CTY_NM,"); // 9
            sql.append("CARD_HLDR_ST_CD,"); // 10
            sql.append("CARD_HLDR_ZIP_CD,"); // 11
            sql.append("CARD_HLDR_WRK_PHN_NBR,"); // 12
            sql.append("CARD_LMT,"); // 13
            sql.append("CARD_CYCLE_AMT_LMT,"); // 14
            sql.append("CARD_CYCLE_VOL_LMT,"); // 15
            sql.append("CARD_STAT_CD,"); // 16
            sql.append("CARD_NTE_TXT,"); // 17
            sql.append("FIN_COA_CD,"); // 18
            sql.append("ACCOUNT_NBR,"); // 19
            sql.append("SUB_ACCT_NBR,"); // 20
            sql.append("FIN_OBJECT_CD,"); // 21
            sql.append("FIN_SUB_OBJ_CD,"); // 22
            sql.append("null,"); // 23
            sql.append("'Y'"); // 24
            sql.append("from fp_prcrmnt_card_hldr_dtl_t");
            
            res = stmt.executeQuery(sql.toString());
            
            ResultSetMetaData rmd = res.getMetaData();
            
            int cnt = 0;
            while (res.next()) {
                for (int i = 0; i < rmd.getColumnCount(); ++i) {
                    pstmt.setObject(i+1, res.getObject(i+1));
                }
                
                try {
                    pstmt.executeUpdate();
                }
                
                catch (SQLException ex) {
                    writeOut("error on record cc_nbr=" + res.getString("CC_NBR") + " - " + ex.toString());
                }
                
                if (((cnt++) % 1000) == 0) {
                    System.out.println(cnt);;
                }
            }
            
            conn.commit();
        }
        
        catch (Exception ex) {
            writeOut(ex);
            try {
                conn.rollback();
            }
            
            catch (Exception ex2) {};
        }
        
        finally {
            closeDbObjects(null, stmt, res);
            closeDbObjects(null, pstmt, null);
        }
    }
    

    private void convertMaintenanceDocuments(Connection upgradeConn) {
        PreparedStatement pstmt = null;
        Statement stmt = null;
        ResultSet res = null;
        
        try {
            writeHeader2("Converting legacy maintenance documents to rice 2.0...");
            
            String fname = properties.getProperty("maintenace-document-conversion-rules-file");
            
            if (StringUtils.isNotBlank(fname)) {
                File f = new File(fname);
            
                if (f.exists()) {
                    MaintainableXMLConversionServiceImpl maintainableXMLConversionServiceImpl = new MaintainableXMLConversionServiceImpl(this, f);
                    EncryptionService encryptService = new EncryptionService(properties.getProperty("encryption-key"));
                    pstmt = upgradeConn.prepareStatement("update krns_maint_doc_test_t set DOC_CNTNT = ? where DOC_HDR_ID = ?");
                    stmt = upgradeConn.createStatement();

                    res = stmt.executeQuery("SELECT DOC_HDR_ID, DOC_CNTNT FROM krns_maint_doc_test_t ");

                    int cnt = 0;
                    long start = System.currentTimeMillis();
                    while (res.next()) {
                        String docid = res.getString(1);
                        String oldXml = null;
                        
                        if (encryptService.isEnabled()) {
                            oldXml = encryptService.decrypt(res.getString(2));
                        } else {
                            oldXml = res.getString(2);
                        }

                        String newXml  = null;
                        
                        try {
                            newXml = maintainableXMLConversionServiceImpl.transformMaintainableXML(oldXml);
                        }

                        catch (Exception ex) {
                            newXml = null;
                            writeOut("error occured while attemptintg to convert document " + docid);
                            writeOut("-------------------------------------------- xml ---------------------------------------------");
                            writeOut(oldXml);
                            writeOut(ex);
                        }

                        if (newXml != null) {
                            if (encryptService.isEnabled()) {
                                pstmt.setString(1, encryptService.encrypt(newXml));
                            } else {
                                pstmt.setString(1, newXml);
                            }

                            pstmt.setString(2, docid);

                            pstmt.addBatch();
                        
                            cnt++;

                            if ((cnt % MAINTENANCE_DOCUMENT_UPDATE_BATCH_SIZE) == 0) {
                                pstmt.executeBatch();
                                
                                if ((cnt % (5 * MAINTENANCE_DOCUMENT_UPDATE_BATCH_SIZE)) == 0) {
                                    writeOut(cnt + " documents processed - " + ((System.currentTimeMillis() - start)/1000) + "sec");
                                    start = System.currentTimeMillis();
                                }
                            }
                        }
                    }

                    if ((cnt % MAINTENANCE_DOCUMENT_UPDATE_BATCH_SIZE) != 0) {
                        pstmt.executeBatch();
                    }

                    doCommit(upgradeConn);

                    writeOut(cnt + " maintenance documents upgraded.");
                } else {
                    writeOut("maintenance document conversion rules file " + f.getPath() + " does not exist");
                }
            } else {
                writeOut("no property 'maintenace-document-conversion-rules-file' entry in property file");
            }
        } 
        
        catch (Exception ex) {
            doRollback(upgradeConn);
            writeOut(ex);
        }
    }
}
