/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.postgresql.PGStatement;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PgStatement;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.BrokenInputStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

@RunWith(Parameterized.class)
public class PreparedStatementTest extends BaseTest4 {

  private static final int NUMERIC_MAX_PRECISION = 1000;
  private static final int NUMERIC_MAX_DISPLAY_SCALE = NUMERIC_MAX_PRECISION;

  public PreparedStatementTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "streamtable", "bin bytea, str text");
    TestUtil.createTable(con, "texttable", "ch char(3), te text, vc varchar(3)");
    TestUtil.createTable(con, "intervaltable", "i interval");
    TestUtil.createTable(con, "inttable", "a int");
    TestUtil.createTable(con, "bool_tab", "bool_val boolean, null_val boolean, tf_val boolean, "
        + "truefalse_val boolean, yn_val boolean, yesno_val boolean, "
        + "onoff_val boolean, onezero_val boolean");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "streamtable");
    TestUtil.dropTable(con, "texttable");
    TestUtil.dropTable(con, "intervaltable");
    TestUtil.dropTable(con, "inttable");
    TestUtil.dropTable(con, "bool_tab");
    super.tearDown();
  }

  private int getNumberOfServerPreparedStatements(String sql)
      throws SQLException {
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    try {
      pstmt = con.prepareStatement(
          "select count(*) from pg_prepared_statements where statement = ?");
      pstmt.setString(1, sql);
      rs = pstmt.executeQuery();
      rs.next();
      return rs.getInt(1);
    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(pstmt);
    }
  }

  @Test
  public void testSetBinaryStream() throws SQLException {
    ByteArrayInputStream bais;
    byte[] buf = new byte[10];
    for (int i = 0; i < buf.length; i++) {
      buf[i] = (byte) i;
    }

    bais = null;
    doSetBinaryStream(bais, 0);

    bais = new ByteArrayInputStream(new byte[0]);
    doSetBinaryStream(bais, 0);

    bais = new ByteArrayInputStream(buf);
    doSetBinaryStream(bais, 0);

    bais = new ByteArrayInputStream(buf);
    doSetBinaryStream(bais, 10);
  }

  @Test
  public void testSetAsciiStream() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, "ASCII"));
    pw.println("Hello");
    pw.flush();

    ByteArrayInputStream bais;

    bais = new ByteArrayInputStream(baos.toByteArray());
    doSetAsciiStream(bais, 0);

    bais = new ByteArrayInputStream(baos.toByteArray());
    doSetAsciiStream(bais, 6);

    bais = new ByteArrayInputStream(baos.toByteArray());
    doSetAsciiStream(bais, 100);
  }

  @Test
  public void testExecuteStringOnPreparedStatement() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT 1");

    try {
      pstmt.executeQuery("SELECT 2");
      fail("Expected an exception when executing a new SQL query on a prepared statement");
    } catch (SQLException e) {
    }

    try {
      pstmt.executeUpdate("UPDATE streamtable SET bin=bin");
      fail("Expected an exception when executing a new SQL update on a prepared statement");
    } catch (SQLException e) {
    }

    try {
      pstmt.execute("UPDATE streamtable SET bin=bin");
      fail("Expected an exception when executing a new SQL statement on a prepared statement");
    } catch (SQLException e) {
    }
  }

  @Test
  public void testBinaryStreamErrorsRestartable() throws SQLException, IOException {
    byte[] buf = new byte[10];
    for (int i = 0; i < buf.length; i++) {
      buf[i] = (byte) i;
    }

    // InputStream is shorter than the length argument implies.
    InputStream is = new ByteArrayInputStream(buf);
    runBrokenStream(is, buf.length + 1);

    // InputStream throws an Exception during read.
    is = new BrokenInputStream(new ByteArrayInputStream(buf), buf.length / 2);
    runBrokenStream(is, buf.length);

    // Invalid length < 0.
    is = new ByteArrayInputStream(buf);
    runBrokenStream(is, -1);

    // Total Bind message length too long.
    is = new ByteArrayInputStream(buf);
    runBrokenStream(is, Integer.MAX_VALUE);
  }

  private void runBrokenStream(InputStream is, int length) throws SQLException, IOException {
    assertThrows(
        "the provided stream length is " + length
            + ", and the number of available bytes on stream length is " + is.available()
            + ", so the bind should fail",
        SQLException.class, () -> {
          try (PreparedStatement pstmt =
                   con.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");) {
            pstmt.setBinaryStream(1, is, length);
            pstmt.setString(2, "Other");
            pstmt.executeUpdate();
          }
        });
    // verify the connection is still valid and the row didn't go in.
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM streamtable");) {
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
    }
  }

  private void doSetBinaryStream(ByteArrayInputStream bais, int length) throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
    pstmt.setBinaryStream(1, bais, length);
    pstmt.setString(2, null);
    pstmt.executeUpdate();
    pstmt.close();
  }

  private void doSetAsciiStream(InputStream is, int length) throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
    pstmt.setBytes(1, null);
    pstmt.setAsciiStream(2, is, length);
    pstmt.executeUpdate();
    pstmt.close();
  }

  @Test
  public void ByteArrayInputStream_setBinaryStream_toString() throws SQLException {
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO streamtable VALUES (?,?)")) {

      byte[] buf = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
      ByteArrayInputStream byteStream = new ByteArrayInputStream(buf);

      pstmt.setBinaryStream(1, byteStream, buf.length);
      assertEquals(
          "InputStream parameter should come as ?, and the second parameter is unset, so it should be ? as well",
          "INSERT INTO streamtable VALUES (?,?)",
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed with half-set parameters"));

      pstmt.setString(2, "test");

      String expected = "INSERT INTO streamtable VALUES (?,('test'))";
      assertEquals(
          "InputStream parameter should come as ? when calling PreparedStatement#toString as we can't process input stream twice yet",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed after setting parameters"));

      assertEquals(
          "InputStream parameter should come as ? when calling PreparedStatement#toString as we can't process input stream twice yet",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "Second PreparedStatement#toString call should succeed as well"));

      pstmt.execute();

      assertEquals(
          "PreparedStatement#toString after .execute()",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed even after execute()"));
    }
  }

  @Test
  public void ByteArrayInputStream_setBinaryStream_addBatch_toString() throws SQLException {
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO streamtable VALUES (?,?)")) {

      pstmt.setBinaryStream(1, new ByteArrayInputStream(new byte[]{0, 1}), 2);
      pstmt.setString(2, "line1");
      pstmt.addBatch();
      pstmt.setBinaryStream(1, new ByteArrayInputStream(new byte[]{0, 1, 2}), 3);
      pstmt.setString(2, "line2");
      pstmt.addBatch();

      String expected = "INSERT INTO streamtable VALUES (?,('line1'));\n"
          + "INSERT INTO streamtable VALUES (?,('line2'))";
      assertEquals(
          "InputStream parameter should come as ? when calling PreparedStatement#toString as we can't process input stream twice yet",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed after addBatch"));

      assertEquals(
          "InputStream parameter should come as ? when calling PreparedStatement#toString as we can't process input stream twice yet",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "Second PreparedStatement#toString call should succeed as well"));

      pstmt.executeBatch();

      assertEquals(
          "PreparedStatement#toString after executeBatch() seem to equal to the latest parameter row",
          "INSERT INTO streamtable VALUES (?,('line2'))",
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed even after executeBatch()"));
    }
  }

  @Test
  public void ByteArray_setBytes_toString() throws SQLException {
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO streamtable VALUES (?,?)")) {

      byte[] buf = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

      pstmt.setBytes(1, buf);

      assertEquals(
          "byte[] parameter could be rendered, and the second parameter is unset, so it should be ? as well",
          "INSERT INTO streamtable VALUES ('\\x00010203040506070809'::bytea,?)",
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed with half-set parameters"));

      pstmt.setString(2, "test");

      String expected = "INSERT INTO streamtable VALUES ('\\x00010203040506070809'::bytea,('test'))";
      assertEquals(
          "byte[] should be rendered when calling PreparedStatement#toString",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed after setting parameters"));

      assertEquals(
          "byte[] should be rendered when calling PreparedStatement#toString",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "Second PreparedStatement#toString call should succeed as well"));

      pstmt.execute();

      assertEquals(
          "PreparedStatement#toString after .execute()",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed even after execute()"));
    }
  }

  @Test
  public void ByteArray_setBytes_addBatch_toString() throws SQLException {
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO streamtable VALUES (?,?)")) {

      pstmt.setBytes(1, new byte[]{0, 1});
      pstmt.setString(2, "line1");
      pstmt.addBatch();

      pstmt.setBytes(1, new byte[]{0, 1, 2});
      pstmt.setString(2, "line2");
      pstmt.addBatch();

      String expected = "INSERT INTO streamtable VALUES ('\\x0001'::bytea,('line1'));\n"
          + "INSERT INTO streamtable VALUES ('\\x000102'::bytea,('line2'))";
      assertEquals(
          "byte[] should be rendered when calling PreparedStatement#toString",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed after addBatch"));

      assertEquals(
          "byte[] should be rendered when calling PreparedStatement#toString",
          expected,
          assertDoesNotThrow(
              pstmt::toString,
              "Second PreparedStatement#toString call should succeed as well"));

      pstmt.executeBatch();

      assertEquals(
          "PreparedStatement#toString after executeBatch() seem to equal to the latest parameter row",
          "INSERT INTO streamtable VALUES ('\\x000102'::bytea,('line2'))",
          assertDoesNotThrow(
              pstmt::toString,
              "PreparedStatement#toString call should succeed even after executeBatch()"));
    }
  }

  @Test
  public void testTrailingSpaces() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("INSERT INTO texttable (ch, te, vc) VALUES (?, ?, ?) ");
    String str = "a  ";
    pstmt.setString(1, str);
    pstmt.setString(2, str);
    pstmt.setString(3, str);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("SELECT ch, te, vc FROM texttable WHERE ch=? AND te=? AND vc=?");
    pstmt.setString(1, str);
    pstmt.setString(2, str);
    pstmt.setString(3, str);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(str, rs.getString(1));
    assertEquals(str, rs.getString(2));
    assertEquals(str, rs.getString(3));
    rs.close();
    pstmt.close();
  }

  @Test
  public void testBinds() throws SQLException {
    // braces around (42) are required to puzzle the parser
    String query = "INSERT INTO inttable(a) VALUES (?);SELECT (42)";
    PreparedStatement ps = con.prepareStatement(query);
    ps.setInt(1, 100500);
    ps.execute();
    ResultSet rs = ps.getResultSet();
    Assert.assertNull("insert produces no results ==> getResultSet should be null", rs);
    Assert.assertTrue("There are two statements => getMoreResults should be true", ps.getMoreResults());
    rs = ps.getResultSet();
    Assert.assertNotNull("select produces results ==> getResultSet should be not null", rs);
    Assert.assertTrue("select produces 1 row ==> rs.next should be true", rs.next());
    Assert.assertEquals("second result of query " + query, 42, rs.getInt(1));

    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(ps);
  }

  @Test
  public void testSetNull() throws SQLException {
    // valid: fully qualified type to setNull()
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO texttable (te) VALUES (?)");
    pstmt.setNull(1, Types.VARCHAR);
    pstmt.executeUpdate();

    // valid: fully qualified type to setObject()
    pstmt.setObject(1, null, Types.VARCHAR);
    pstmt.executeUpdate();

    // valid: setObject() with partial type info and a typed "null object instance"
    org.postgresql.util.PGobject dummy = new org.postgresql.util.PGobject();
    dummy.setType("text");
    dummy.setValue(null);
    pstmt.setObject(1, dummy, Types.OTHER);
    pstmt.executeUpdate();

    // setObject() with no type info
    pstmt.setObject(1, null);
    pstmt.executeUpdate();

    // setObject() with insufficient type info
    pstmt.setObject(1, null, Types.OTHER);
    pstmt.executeUpdate();

    // setNull() with insufficient type info
    pstmt.setNull(1, Types.OTHER);
    pstmt.executeUpdate();

    pstmt.close();

    assumeMinimumServerVersion(ServerVersion.v8_3);
    pstmt = con.prepareStatement("select 'ok' where ?=? or (? is null) ");
    pstmt.setObject(1, UUID.randomUUID(), Types.OTHER);
    pstmt.setNull(2, Types.OTHER, "uuid");
    pstmt.setNull(3, Types.OTHER, "uuid");
    ResultSet rs = pstmt.executeQuery();

    assertTrue(rs.next());
    assertEquals("ok", rs.getObject(1));

    rs.close();
    pstmt.close();

  }

  @Test
  public void testSingleQuotes() throws SQLException {
    String[] testStrings = new String[]{
      "bare ? question mark",
      "quoted \\' single quote",
      "doubled '' single quote",
      "octal \\060 constant",
      "escaped \\? question mark",
      "double \\\\ backslash",
      "double \" quote",};

    String[] testStringsStdConf = new String[]{
      "bare ? question mark",
      "quoted '' single quote",
      "doubled '' single quote",
      "octal 0 constant",
      "escaped ? question mark",
      "double \\ backslash",
      "double \" quote",};

    String[] expected = new String[]{
      "bare ? question mark",
      "quoted ' single quote",
      "doubled ' single quote",
      "octal 0 constant",
      "escaped ? question mark",
      "double \\ backslash",
      "double \" quote",};

    boolean oldStdStrings = TestUtil.getStandardConformingStrings(con);
    Statement stmt = con.createStatement();

    // Test with standard_conforming_strings turned off.
    stmt.execute("SET standard_conforming_strings TO off");
    for (int i = 0; i < testStrings.length; i++) {
      PreparedStatement pstmt = con.prepareStatement("SELECT '" + testStrings[i] + "'");
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(expected[i], rs.getString(1));
      rs.close();
      pstmt.close();
    }

    // Test with standard_conforming_strings turned off...
    // ... using the escape string syntax (E'').
    stmt.execute("SET standard_conforming_strings TO on");
    for (int i = 0; i < testStrings.length; i++) {
      PreparedStatement pstmt = con.prepareStatement("SELECT E'" + testStrings[i] + "'");
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(expected[i], rs.getString(1));
      rs.close();
      pstmt.close();
    }
    // ... using standard conforming input strings.
    for (int i = 0; i < testStrings.length; i++) {
      PreparedStatement pstmt = con.prepareStatement("SELECT '" + testStringsStdConf[i] + "'");
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(expected[i], rs.getString(1));
      rs.close();
      pstmt.close();
    }

    stmt.execute("SET standard_conforming_strings TO " + (oldStdStrings ? "on" : "off"));
    stmt.close();
  }

  @Test
  public void testDoubleQuotes() throws SQLException {
    String[] testStrings = new String[]{
        "bare ? question mark",
        "single ' quote",
        "doubled '' single quote",
        "doubled \"\" double quote",
        "no backslash interpretation here: \\",
    };

    for (String testString : testStrings) {
      PreparedStatement pstmt =
          con.prepareStatement("CREATE TABLE \"" + testString + "\" (i integer)");
      pstmt.executeUpdate();
      pstmt.close();

      pstmt = con.prepareStatement("DROP TABLE \"" + testString + "\"");
      pstmt.executeUpdate();
      pstmt.close();
    }
  }

  @Test
  public void testDollarQuotes() throws SQLException {
    // dollar-quotes are supported in the backend since version 8.0
    PreparedStatement st;
    ResultSet rs;

    st = con.prepareStatement("SELECT $$;$$ WHERE $x$?$x$=$_0$?$_0$ AND $$?$$=?");
    st.setString(1, "?");
    rs = st.executeQuery();
    assertTrue(rs.next());
    assertEquals(";", rs.getString(1));
    assertFalse(rs.next());
    st.close();

    st = con.prepareStatement(
        "SELECT $__$;$__$ WHERE ''''=$q_1$'$q_1$ AND ';'=?;"
            + "SELECT $x$$a$;$x $a$$x$ WHERE $$;$$=? OR ''=$c$c$;$c$;"
            + "SELECT ?");
    st.setString(1, ";");
    st.setString(2, ";");
    st.setString(3, "$a$ $a$");

    assertTrue(st.execute());
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(";", rs.getString(1));
    assertFalse(rs.next());

    assertTrue(st.getMoreResults());
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("$a$;$x $a$", rs.getString(1));
    assertFalse(rs.next());

    assertTrue(st.getMoreResults());
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("$a$ $a$", rs.getString(1));
    assertFalse(rs.next());
    st.close();
  }

  @Test
  public void testDollarQuotesAndIdentifiers() throws SQLException {
    // dollar-quotes are supported in the backend since version 8.0
    PreparedStatement st;

    con.createStatement().execute("CREATE TEMP TABLE a$b$c(a varchar, b varchar)");
    st = con.prepareStatement("INSERT INTO a$b$c (a, b) VALUES (?, ?)");
    st.setString(1, "a");
    st.setString(2, "b");
    st.executeUpdate();
    st.close();

    con.createStatement().execute("CREATE TEMP TABLE e$f$g(h varchar, e$f$g varchar) ");
    st = con.prepareStatement("UPDATE e$f$g SET h = ? || e$f$g");
    st.setString(1, "a");
    st.executeUpdate();
    st.close();
  }

  @Test
  public void testComments() throws SQLException {
    PreparedStatement st;
    ResultSet rs;

    st = con.prepareStatement("SELECT /*?*/ /*/*/*/**/*/*/*/1;SELECT ?;--SELECT ?");
    st.setString(1, "a");
    assertTrue(st.execute());
    assertTrue(st.getMoreResults());
    assertFalse(st.getMoreResults());
    st.close();

    st = con.prepareStatement("SELECT /**/'?'/*/**/*/ WHERE '?'=/*/*/*?*/*/*/--?\n?");
    st.setString(1, "?");
    rs = st.executeQuery();
    assertTrue(rs.next());
    assertEquals("?", rs.getString(1));
    assertFalse(rs.next());
    st.close();
  }

  @Test
  public void testDoubleQuestionMark() throws SQLException {
    PreparedStatement st;
    ResultSet rs;

    st = con.prepareStatement("select ??- lseg '((-1,0),(1,0))';");
    rs = st.executeQuery();
    assertTrue(rs.next());
    // Bool values in binary mode are first converted to their Java type (Boolean), and then
    // converted to String, which means that we receive 'true'. Bool values in text mode are
    // returned as the same text value that was returned by the server, i.e. 't'.
    assertEquals(binaryMode == BinaryMode.FORCE && preferQueryMode != PreferQueryMode.SIMPLE ? "true" : "t", rs.getString(1));
    assertFalse(rs.next());
    st.close();

    st = con.prepareStatement("select lseg '((-1,0),(1,0))' ??# box '((-2,-2),(2,2))';");
    rs = st.executeQuery();
    assertTrue(rs.next());
    assertEquals(binaryMode == BinaryMode.FORCE && preferQueryMode != PreferQueryMode.SIMPLE ? "true" : "t", rs.getString(1));
    assertFalse(rs.next());
    st.close();
  }

  @Test
  public void testNumeric() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE TEMP TABLE numeric_tab (max_numeric_positive numeric, min_numeric_positive numeric, max_numeric_negative numeric, min_numeric_negative numeric, null_value numeric)");
    pstmt.executeUpdate();
    pstmt.close();

    char[] wholeDigits = new char[NUMERIC_MAX_DISPLAY_SCALE];
    for (int i = 0; i < NUMERIC_MAX_DISPLAY_SCALE; i++) {
      wholeDigits[i] = '9';
    }

    char[] fractionDigits = new char[NUMERIC_MAX_PRECISION];
    for (int i = 0; i < NUMERIC_MAX_PRECISION; i++) {
      fractionDigits[i] = '9';
    }

    String maxValueString = new String(wholeDigits);
    String minValueString = new String(fractionDigits);
    BigDecimal[] values = new BigDecimal[4];
    values[0] = new BigDecimal(maxValueString);
    values[1] = new BigDecimal("-" + maxValueString);
    values[2] = new BigDecimal(minValueString);
    values[3] = new BigDecimal("-" + minValueString);

    pstmt = con.prepareStatement("insert into numeric_tab values (?,?,?,?,?)");
    for (int i = 1; i < 5; i++) {
      pstmt.setBigDecimal(i, values[i - 1]);
    }

    pstmt.setNull(5, Types.NUMERIC);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from numeric_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    for (int i = 1; i < 5; i++) {
      assertTrue(rs.getBigDecimal(i).compareTo(values[i - 1]) == 0);
    }
    rs.getDouble(5);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testDouble() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE TEMP TABLE double_tab (max_double float, min_double float, null_value float)");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("insert into double_tab values (?,?,?)");
    pstmt.setDouble(1, 1.0E125);
    pstmt.setDouble(2, 1.0E-130);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from double_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    double d = rs.getDouble(1);
    assertTrue(rs.getDouble(1) == 1.0E125);
    assertTrue(rs.getDouble(2) == 1.0E-130);
    rs.getDouble(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testFloat() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE TEMP TABLE float_tab (max_float real, min_float real, null_value real)");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setFloat(1, (float) 1.0E37);
    pstmt.setFloat(2, (float) 1.0E-37);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    float f = rs.getFloat(1);
    assertTrue("expected 1.0E37,received " + rs.getFloat(1), rs.getFloat(1) == (float) 1.0E37);
    assertTrue("expected 1.0E-37,received " + rs.getFloat(2), rs.getFloat(2) == (float) 1.0E-37);
    rs.getDouble(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testNaNLiteralsSimpleStatement() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select 'NaN'::numeric, 'NaN'::real, 'NaN'::double precision");
    checkNaNLiterals(stmt, rs);
  }

  @Test
  public void testNaNLiteralsPreparedStatement() throws SQLException {
    PreparedStatement stmt = con.prepareStatement("select 'NaN'::numeric, 'NaN'::real, 'NaN'::double precision");
    checkNaNLiterals(stmt, stmt.executeQuery());
  }

  private static void checkNaNLiterals(Statement stmt, ResultSet rs) throws SQLException {
    rs.next();
    assertTrue("Double.isNaN((Double) rs.getObject", Double.isNaN((Double) rs.getObject(3)));
    assertTrue("Double.isNaN(rs.getDouble", Double.isNaN(rs.getDouble(3)));
    assertTrue("Float.isNaN((Float) rs.getObject", Float.isNaN((Float) rs.getObject(2)));
    assertTrue("Float.isNaN(rs.getFloat", Float.isNaN(rs.getFloat(2)));
    assertTrue("Double.isNaN((Double) rs.getObject", Double.isNaN((Double) rs.getObject(1)));
    assertTrue("Double.isNaN(rs.getDouble", Double.isNaN(rs.getDouble(1)));
    try {
      rs.getBigDecimal(1);
      fail("NaN::numeric rs.getBigDecimal");
    } catch (SQLException e) {
      assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), e.getSQLState());
      assertEquals(GT.tr("Bad value for type {0} : {1}", "BigDecimal", "NaN"), e.getMessage());
    }

    rs.close();
    stmt.close();
  }

  @Test
  public void testInfinityLiteralsSimpleStatement() throws SQLException {
    assumeMinimumServerVersion("v14 introduced 'Infinity'::numeric", ServerVersion.v14);

    String query = "SELECT 'Infinity'::numeric, 'Infinity'::real, 'Infinity'::double precision, "
        + "'-Infinity'::numeric, '-Infinity'::real, '-Infinity'::double precision";
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery(query)) {
      checkInfinityLiterals(rs);
    }
  }

  @Test
  public void testInfinityLiteralsPreparedStatement() throws SQLException {
    assumeMinimumServerVersion("v14 introduced 'Infinity'::numeric", ServerVersion.v14);

    String query = "SELECT 'Infinity'::numeric, 'Infinity'::real, 'Infinity'::double precision, "
        + "'-Infinity'::numeric, '-Infinity'::real, '-Infinity'::double precision";
    try (PreparedStatement stmt = con.prepareStatement(query);
         ResultSet rs = stmt.executeQuery()) {
      checkInfinityLiterals(rs);
    }
  }

  private static void checkInfinityLiterals(ResultSet rs) throws SQLException {
    rs.next();
    assertEquals("inf numeric rs.getObject", Double.POSITIVE_INFINITY, rs.getObject(1));
    assertEquals("inf numeric rs.getDouble", Double.POSITIVE_INFINITY, rs.getDouble(1), 0.0);
    assertEquals("inf real rs.getObject", Float.POSITIVE_INFINITY, rs.getObject(2));
    assertEquals("inf real rs.getFloat", Float.POSITIVE_INFINITY, rs.getFloat(2), 0.0);
    assertEquals("inf double precision rs.getObject", Double.POSITIVE_INFINITY, rs.getObject(3));
    assertEquals("inf double precision rs.getDouble", Double.POSITIVE_INFINITY, rs.getDouble(3), 0.0);

    assertEquals("-inf numeric rs.getObject", Double.NEGATIVE_INFINITY, rs.getObject(4));
    assertEquals("-inf numeric rs.getDouble", Double.NEGATIVE_INFINITY, rs.getDouble(4), 0.0);
    assertEquals("-inf real rs.getObject", Float.NEGATIVE_INFINITY, rs.getObject(5));
    assertEquals("-inf real rs.getFloat", Float.NEGATIVE_INFINITY, rs.getFloat(5), 0.0);
    assertEquals("-inf double precision rs.getObject", Double.NEGATIVE_INFINITY, rs.getObject(6));
    assertEquals("-inf double precision rs.getDouble", Double.NEGATIVE_INFINITY, rs.getDouble(6), 0.0);

    try {
      rs.getBigDecimal(1);
      fail("inf numeric rs.getBigDecimal");
    } catch (SQLException e) {
      assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), e.getSQLState());
      assertEquals(GT.tr("Bad value for type {0} : {1}", "BigDecimal", "Infinity"), e.getMessage());
    }

    try {
      rs.getBigDecimal(4);
      fail("-inf numeric rs.getBigDecimal");
    } catch (SQLException e) {
      assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), e.getSQLState());
      assertEquals(GT.tr("Bad value for type {0} : {1}", "BigDecimal", "-Infinity"), e.getMessage());
    }
  }

  @Test
  public void testSpecialSetDoubleFloat() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select ?, ?, ?, ?, ?, ?");
    ps.setFloat(1, Float.NaN);
    ps.setDouble(2, Double.NaN);
    ps.setFloat(3, Float.POSITIVE_INFINITY);
    ps.setDouble(4, Double.POSITIVE_INFINITY);
    ps.setFloat(5, Float.NEGATIVE_INFINITY);
    ps.setDouble(6, Double.NEGATIVE_INFINITY);

    checkNaNParams(ps);
  }

  @Test
  public void testSpecialSetObject() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select ?, ?, ?, ?, ?, ?");
    ps.setObject(1, Float.NaN);
    ps.setObject(2, Double.NaN);
    ps.setObject(3, Float.POSITIVE_INFINITY);
    ps.setObject(4, Double.POSITIVE_INFINITY);
    ps.setObject(5, Float.NEGATIVE_INFINITY);
    ps.setObject(6, Double.NEGATIVE_INFINITY);

    checkNaNParams(ps);
  }

  private static void checkNaNParams(PreparedStatement ps) throws SQLException {
    ResultSet rs = ps.executeQuery();
    rs.next();

    assertTrue("Float.isNaN((Float) rs.getObject", Float.isNaN((Float) rs.getObject(1)));
    assertTrue("Float.isNaN(rs.getFloat", Float.isNaN(rs.getFloat(1)));
    assertTrue("Double.isNaN((Double) rs.getObject", Double.isNaN((Double) rs.getObject(2)));
    assertTrue("Double.isNaN(rs.getDouble", Double.isNaN(rs.getDouble(2)));
    assertEquals("Float.POSITIVE_INFINITY rs.getObject", Float.POSITIVE_INFINITY, rs.getObject(3));
    assertEquals("Float.POSITIVE_INFINITY rs.getFloat", Float.POSITIVE_INFINITY, rs.getFloat(3), 0);
    assertEquals("Double.POSITIVE_INFINITY rs.getObject", Double.POSITIVE_INFINITY, rs.getObject(4));
    assertEquals("Double.POSITIVE_INFINITY rs.getDouble", Double.POSITIVE_INFINITY, rs.getDouble(4), 0);
    assertEquals("Float.NEGATIVE_INFINITY rs.getObject", Float.NEGATIVE_INFINITY, rs.getObject(5));
    assertEquals("Float.NEGATIVE_INFINITY rs.getFloat", Float.NEGATIVE_INFINITY, rs.getFloat(5), 0);
    assertEquals("Double.NEGATIVE_INFINITY rs.getObject", Double.NEGATIVE_INFINITY, rs.getObject(6));
    assertEquals("Double.NEGATIVE_INFINITY rs.getDouble", Double.NEGATIVE_INFINITY, rs.getDouble(6), 0);

    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(ps);
  }

  @Test
  public void testBoolean() throws SQLException {
    testBoolean(0);
    testBoolean(1);
    testBoolean(5);
    testBoolean(-1);
  }

  public void testBoolean(int prepareThreshold) throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("insert into bool_tab values (?,?,?,?,?,?,?,?)");
    ((org.postgresql.PGStatement) pstmt).setPrepareThreshold(prepareThreshold);

    // Test TRUE values
    pstmt.setBoolean(1, true);
    pstmt.setObject(1, Boolean.TRUE);
    pstmt.setNull(2, Types.BIT);
    pstmt.setObject(3, 't', Types.BIT);
    pstmt.setObject(3, 'T', Types.BIT);
    pstmt.setObject(3, "t", Types.BIT);
    pstmt.setObject(4, "true", Types.BIT);
    pstmt.setObject(5, 'y', Types.BIT);
    pstmt.setObject(5, 'Y', Types.BIT);
    pstmt.setObject(5, "Y", Types.BIT);
    pstmt.setObject(6, "YES", Types.BIT);
    pstmt.setObject(7, "On", Types.BIT);
    pstmt.setObject(8, '1', Types.BIT);
    pstmt.setObject(8, "1", Types.BIT);
    assertEquals("one row inserted, true values", 1, pstmt.executeUpdate());
    // Test FALSE values
    pstmt.setBoolean(1, false);
    pstmt.setObject(1, Boolean.FALSE);
    pstmt.setNull(2, Types.BOOLEAN);
    pstmt.setObject(3, 'f', Types.BOOLEAN);
    pstmt.setObject(3, 'F', Types.BOOLEAN);
    pstmt.setObject(3, "F", Types.BOOLEAN);
    pstmt.setObject(4, "false", Types.BOOLEAN);
    pstmt.setObject(5, 'n', Types.BOOLEAN);
    pstmt.setObject(5, 'N', Types.BOOLEAN);
    pstmt.setObject(5, "N", Types.BOOLEAN);
    pstmt.setObject(6, "NO", Types.BOOLEAN);
    pstmt.setObject(7, "Off", Types.BOOLEAN);
    pstmt.setObject(8, "0", Types.BOOLEAN);
    pstmt.setObject(8, '0', Types.BOOLEAN);
    assertEquals("one row inserted, false values", 1, pstmt.executeUpdate());
    // Test weird values
    pstmt.setObject(1, (byte) 0, Types.BOOLEAN);
    pstmt.setObject(2, BigDecimal.ONE, Types.BOOLEAN);
    pstmt.setObject(3, 0L, Types.BOOLEAN);
    pstmt.setObject(4, 0x1, Types.BOOLEAN);
    pstmt.setObject(5, (float) 0, Types.BOOLEAN);
    pstmt.setObject(5, 1.0d, Types.BOOLEAN);
    pstmt.setObject(5, 0.0f, Types.BOOLEAN);
    pstmt.setObject(6, Integer.valueOf("1"), Types.BOOLEAN);
    pstmt.setObject(7, new java.math.BigInteger("0"), Types.BOOLEAN);
    pstmt.clearParameters();
    pstmt.close();

    pstmt = con.prepareStatement("select * from bool_tab");
    ((org.postgresql.PGStatement) pstmt).setPrepareThreshold(prepareThreshold);
    ResultSet rs = pstmt.executeQuery();

    assertTrue(rs.next());
    assertTrue("expected true, received " + rs.getBoolean(1), rs.getBoolean(1));
    rs.getFloat(2);
    assertTrue(rs.wasNull());
    assertTrue("expected true, received " + rs.getBoolean(3), rs.getBoolean(3));
    assertTrue("expected true, received " + rs.getBoolean(4), rs.getBoolean(4));
    assertTrue("expected true, received " + rs.getBoolean(5), rs.getBoolean(5));
    assertTrue("expected true, received " + rs.getBoolean(6), rs.getBoolean(6));
    assertTrue("expected true, received " + rs.getBoolean(7), rs.getBoolean(7));
    assertTrue("expected true, received " + rs.getBoolean(8), rs.getBoolean(8));

    assertTrue(rs.next());
    assertFalse("expected false, received " + rs.getBoolean(1), rs.getBoolean(1));
    rs.getBoolean(2);
    assertTrue(rs.wasNull());
    assertFalse("expected false, received " + rs.getBoolean(3), rs.getBoolean(3));
    assertFalse("expected false, received " + rs.getBoolean(4), rs.getBoolean(4));
    assertFalse("expected false, received " + rs.getBoolean(5), rs.getBoolean(5));
    assertFalse("expected false, received " + rs.getBoolean(6), rs.getBoolean(6));
    assertFalse("expected false, received " + rs.getBoolean(7), rs.getBoolean(7));
    assertFalse("expected false, received " + rs.getBoolean(8), rs.getBoolean(8));

    rs.close();
    pstmt.close();

    pstmt = con.prepareStatement("TRUNCATE TABLE bool_tab");
    pstmt.executeUpdate();
    pstmt.close();
  }

  @Test
  public void testBadBoolean() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO bad_bool VALUES (?)");
    try {
      pstmt.setObject(1, "this is not boolean", Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"this is not boolean\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, 'X', Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"X\"", e.getMessage());
    }
    try {
      java.io.File obj = new java.io.File("");
      pstmt.setObject(1, obj, Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean", e.getMessage());
    }
    try {
      pstmt.setObject(1, "1.0", Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"1.0\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, "-1", Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"-1\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, "ok", Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"ok\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, 0.99f, Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"0.99\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, -0.01d, Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"-0.01\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, new java.sql.Date(0), Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean", e.getMessage());
    }
    try {
      pstmt.setObject(1, new java.math.BigInteger("1000"), Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"1000\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, Math.PI, Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"3.141592653589793\"", e.getMessage());
    }
    pstmt.close();
  }

  @Test
  public void testSetFloatInteger() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE float_tab (max_val float8, min_val float, null_val float8)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInteger = 2147483647;
    Integer minInteger = -2147483648;

    Double maxFloat = 2147483647.0;
    Double minFloat = (double) -2147483648;

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setObject(1, maxInteger, Types.FLOAT);
    pstmt.setObject(2, minInteger, Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + maxFloat + " ,received " + rs.getObject(1),
        rs.getObject(1).equals(maxFloat));
    assertTrue("expected " + minFloat + " ,received " + rs.getObject(2),
        rs.getObject(2).equals(minFloat));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetFloatString() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE float_tab (max_val float8, min_val float8, null_val float8)");
    pstmt.executeUpdate();
    pstmt.close();

    String maxStringFloat = "1.0E37";
    String minStringFloat = "1.0E-37";
    Double maxFloat = 1.0E37;
    Double minFloat = 1.0E-37;

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setObject(1, maxStringFloat, Types.FLOAT);
    pstmt.setObject(2, minStringFloat, Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.setObject(1, "1.0", Types.FLOAT);
    pstmt.setObject(2, "0.0", Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue(((Double) rs.getObject(1)).equals(maxFloat));
    assertTrue(((Double) rs.getObject(2)).equals(minFloat));
    assertTrue(rs.getDouble(1) == maxFloat);
    assertTrue(rs.getDouble(2) == minFloat);
    rs.getFloat(3);
    assertTrue(rs.wasNull());

    assertTrue(rs.next());
    assertTrue("expected true, received " + rs.getBoolean(1), rs.getBoolean(1));
    assertFalse("expected false,received " + rs.getBoolean(2), rs.getBoolean(2));

    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetFloatBigDecimal() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE float_tab (max_val float8, min_val float8, null_val float8)");
    pstmt.executeUpdate();
    pstmt.close();

    BigDecimal maxBigDecimalFloat = new BigDecimal("1.0E37");
    BigDecimal minBigDecimalFloat = new BigDecimal("1.0E-37");
    Double maxFloat = 1.0E37;
    Double minFloat = 1.0E-37;

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setObject(1, maxBigDecimalFloat, Types.FLOAT);
    pstmt.setObject(2, minBigDecimalFloat, Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + maxFloat + " ,received " + rs.getObject(1),
        ((Double) rs.getObject(1)).equals(maxFloat));
    assertTrue("expected " + minFloat + " ,received " + rs.getObject(2),
        ((Double) rs.getObject(2)).equals(minFloat));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetTinyIntFloat() throws SQLException {
    PreparedStatement pstmt = con
        .prepareStatement("CREATE temp TABLE tiny_int (max_val int4, min_val int4, null_val int4)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInt = 127;
    Integer minInt = -127;
    Float maxIntFloat = 127F;
    Float minIntFloat = (float) -127;

    pstmt = con.prepareStatement("insert into tiny_int values (?,?,?)");
    pstmt.setObject(1, maxIntFloat, Types.TINYINT);
    pstmt.setObject(2, minIntFloat, Types.TINYINT);
    pstmt.setNull(3, Types.TINYINT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from tiny_int");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals("maxInt as rs.getObject", maxInt, rs.getObject(1));
    assertEquals("minInt as rs.getObject", minInt, rs.getObject(2));
    rs.getObject(3);
    assertTrue("rs.wasNull after rs.getObject", rs.wasNull());
    assertEquals("maxInt as rs.getInt", maxInt, (Integer) rs.getInt(1));
    assertEquals("minInt as rs.getInt", minInt, (Integer) rs.getInt(2));
    rs.getInt(3);
    assertTrue("rs.wasNull after rs.getInt", rs.wasNull());
    assertEquals("maxInt as rs.getLong", Long.valueOf(maxInt), (Long) rs.getLong(1));
    assertEquals("minInt as rs.getLong", Long.valueOf(minInt), (Long) rs.getLong(2));
    rs.getLong(3);
    assertTrue("rs.wasNull after rs.getLong", rs.wasNull());
    assertEquals("maxInt as rs.getBigDecimal", BigDecimal.valueOf(maxInt), rs.getBigDecimal(1));
    assertEquals("minInt as rs.getBigDecimal", BigDecimal.valueOf(minInt), rs.getBigDecimal(2));
    assertNull("rs.getBigDecimal", rs.getBigDecimal(3));
    assertTrue("rs.getBigDecimal after rs.getLong", rs.wasNull());
    assertEquals("maxInt as rs.getBigDecimal(scale=0)", BigDecimal.valueOf(maxInt),
        rs.getBigDecimal(1, 0));
    assertEquals("minInt as rs.getBigDecimal(scale=0)", BigDecimal.valueOf(minInt),
        rs.getBigDecimal(2, 0));
    assertNull("rs.getBigDecimal(scale=0)", rs.getBigDecimal(3, 0));
    assertTrue("rs.getBigDecimal after rs.getLong", rs.wasNull());
    assertEquals("maxInt as rs.getBigDecimal(scale=1)",
        BigDecimal.valueOf(maxInt).setScale(1, RoundingMode.HALF_EVEN), rs.getBigDecimal(1, 1));
    assertEquals("minInt as rs.getBigDecimal(scale=1)",
        BigDecimal.valueOf(minInt).setScale(1, RoundingMode.HALF_EVEN), rs.getBigDecimal(2, 1));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetSmallIntFloat() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE small_int (max_val int4, min_val int4, null_val int4)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInt = 32767;
    Integer minInt = -32768;
    Float maxIntFloat = 32767F;
    Float minIntFloat = (float) -32768;

    pstmt = con.prepareStatement("insert into small_int values (?,?,?)");
    pstmt.setObject(1, maxIntFloat, Types.SMALLINT);
    pstmt.setObject(2, minIntFloat, Types.SMALLINT);
    pstmt.setNull(3, Types.TINYINT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from small_int");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + maxInt + " ,received " + rs.getObject(1),
        rs.getObject(1).equals(maxInt));
    assertTrue("expected " + minInt + " ,received " + rs.getObject(2),
        rs.getObject(2).equals(minInt));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();
  }

  @Test
  public void testSetIntFloat() throws SQLException {
    PreparedStatement pstmt = con
        .prepareStatement("CREATE temp TABLE int_TAB (max_val int4, min_val int4, null_val int4)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInt = 1000;
    Integer minInt = -1000;
    Float maxIntFloat = 1000F;
    Float minIntFloat = (float) -1000;

    pstmt = con.prepareStatement("insert into int_tab values (?,?,?)");
    pstmt.setObject(1, maxIntFloat, Types.INTEGER);
    pstmt.setObject(2, minIntFloat, Types.INTEGER);
    pstmt.setNull(3, Types.INTEGER);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from int_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + maxInt + " ,received " + rs.getObject(1),
        ((Integer) rs.getObject(1)).equals(maxInt));
    assertTrue("expected " + minInt + " ,received " + rs.getObject(2),
        ((Integer) rs.getObject(2)).equals(minInt));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetBooleanDouble() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE double_tab (max_val float, min_val float, null_val float)");
    pstmt.executeUpdate();
    pstmt.close();

    Double dBooleanTrue = 1.0;
    Double dBooleanFalse = (double) 0;

    pstmt = con.prepareStatement("insert into double_tab values (?,?,?)");
    pstmt.setObject(1, Boolean.TRUE, Types.DOUBLE);
    pstmt.setObject(2, Boolean.FALSE, Types.DOUBLE);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from double_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + dBooleanTrue + " ,received " + rs.getObject(1),
        rs.getObject(1).equals(dBooleanTrue));
    assertTrue("expected " + dBooleanFalse + " ,received " + rs.getObject(2),
        rs.getObject(2).equals(dBooleanFalse));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetBooleanNumeric() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE numeric_tab (max_val numeric(30,15), min_val numeric(30,15), null_val numeric(30,15))");
    pstmt.executeUpdate();
    pstmt.close();

    BigDecimal dBooleanTrue = new BigDecimal(1);
    BigDecimal dBooleanFalse = new BigDecimal(0);

    pstmt = con.prepareStatement("insert into numeric_tab values (?,?,?)");
    pstmt.setObject(1, Boolean.TRUE, Types.NUMERIC, 2);
    pstmt.setObject(2, Boolean.FALSE, Types.NUMERIC, 2);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from numeric_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + dBooleanTrue + " ,received " + rs.getObject(1),
        ((BigDecimal) rs.getObject(1)).compareTo(dBooleanTrue) == 0);
    assertTrue("expected " + dBooleanFalse + " ,received " + rs.getObject(2),
        ((BigDecimal) rs.getObject(2)).compareTo(dBooleanFalse) == 0);
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetBooleanDecimal() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE DECIMAL_TAB (max_val numeric(30,15), min_val numeric(30,15), null_val numeric(30,15))");
    pstmt.executeUpdate();
    pstmt.close();

    BigDecimal dBooleanTrue = new BigDecimal(1);
    BigDecimal dBooleanFalse = new BigDecimal(0);

    pstmt = con.prepareStatement("insert into DECIMAL_TAB values (?,?,?)");
    pstmt.setObject(1, Boolean.TRUE, Types.DECIMAL, 2);
    pstmt.setObject(2, Boolean.FALSE, Types.DECIMAL, 2);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from DECIMAL_TAB");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + dBooleanTrue + " ,received " + rs.getObject(1),
        ((BigDecimal) rs.getObject(1)).compareTo(dBooleanTrue) == 0);
    assertTrue("expected " + dBooleanFalse + " ,received " + rs.getObject(2),
        ((BigDecimal) rs.getObject(2)).compareTo(dBooleanFalse) == 0);
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetObjectBigDecimalUnscaled() throws SQLException {
    TestUtil.createTempTable(con, "decimal_scale",
        "n1 numeric, n2 numeric, n3 numeric, n4 numeric");
    PreparedStatement pstmt = con.prepareStatement("insert into decimal_scale values(?,?,?,?)");
    BigDecimal v = new BigDecimal("3.141593");
    pstmt.setObject(1, v, Types.NUMERIC);

    String vs = v.toPlainString();
    pstmt.setObject(2, vs, Types.NUMERIC);

    Float vf = Float.valueOf(vs);
    pstmt.setObject(3, vf, Types.NUMERIC);

    Double vd = Double.valueOf(vs);
    pstmt.setObject(4, vd, Types.NUMERIC);

    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select n1,n2,n3,n4 from decimal_scale");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertTrue("expected numeric set via BigDecimal " + v + " stored as " + rs.getBigDecimal(1),
        v.compareTo(rs.getBigDecimal(1)) == 0);
    assertTrue("expected numeric set via String" + vs + " stored as " + rs.getBigDecimal(2),
        v.compareTo(rs.getBigDecimal(2)) == 0);
    // float is really bad...
    assertTrue("expected numeric set via Float" + vf + " stored as " + rs.getBigDecimal(3),
        v.compareTo(rs.getBigDecimal(3).setScale(6, RoundingMode.HALF_UP)) == 0);
    assertTrue("expected numeric set via Double" + vd + " stored as " + rs.getBigDecimal(4),
        v.compareTo(rs.getBigDecimal(4)) == 0);

    rs.close();
    pstmt.close();
  }

  @Test
  public void testSetObjectBigDecimalWithScale() throws SQLException {
    TestUtil.createTempTable(con, "decimal_scale",
        "n1 numeric, n2 numeric, n3 numeric, n4 numeric");
    PreparedStatement psinsert = con.prepareStatement("insert into decimal_scale values(?,?,?,?)");
    PreparedStatement psselect = con.prepareStatement("select n1,n2,n3,n4 from decimal_scale");
    PreparedStatement pstruncate = con.prepareStatement("truncate table decimal_scale");

    BigDecimal v = new BigDecimal("3.141593");
    String vs = v.toPlainString();
    Float vf = Float.valueOf(vs);
    Double vd = Double.valueOf(vs);

    for (int s = 0; s < 6; s++) {
      psinsert.setObject(1, v, Types.NUMERIC, s);
      psinsert.setObject(2, vs, Types.NUMERIC, s);
      psinsert.setObject(3, vf, Types.NUMERIC, s);
      psinsert.setObject(4, vd, Types.NUMERIC, s);

      psinsert.executeUpdate();

      ResultSet rs = psselect.executeQuery();
      assertTrue(rs.next());
      BigDecimal vscaled = v.setScale(s, RoundingMode.HALF_UP);
      assertTrue(
          "expected numeric set via BigDecimal " + v + " with scale " + s + " stored as " + vscaled,
          vscaled.compareTo(rs.getBigDecimal(1)) == 0);
      assertTrue(
          "expected numeric set via String" + vs + " with scale " + s + " stored as " + vscaled,
          vscaled.compareTo(rs.getBigDecimal(2)) == 0);
      assertTrue(
          "expected numeric set via Float" + vf + " with scale " + s + " stored as " + vscaled,
          vscaled.compareTo(rs.getBigDecimal(3)) == 0);
      assertTrue(
          "expected numeric set via Double" + vd + " with scale " + s + " stored as " + vscaled,
          vscaled.compareTo(rs.getBigDecimal(4)) == 0);
      rs.close();
      pstruncate.executeUpdate();
    }

    psinsert.close();
    psselect.close();
    pstruncate.close();
  }

  @Test
  public void testSetObjectWithBigDecimal() throws SQLException {
    TestUtil.createTempTable(con, "number_fallback",
            "n1 numeric");
    PreparedStatement psinsert = con.prepareStatement("insert into number_fallback values(?)");
    PreparedStatement psselect = con.prepareStatement("select n1 from number_fallback");

    psinsert.setObject(1, new BigDecimal("733"));
    psinsert.execute();

    ResultSet rs = psselect.executeQuery();
    assertTrue(rs.next());
    assertTrue(
        "expected 733, but received " + rs.getBigDecimal(1),
        new BigDecimal("733").compareTo(rs.getBigDecimal(1)) == 0);

    psinsert.close();
    psselect.close();
  }

  @Test
  public void testSetObjectNumberFallbackWithBigInteger() throws SQLException {
    TestUtil.createTempTable(con, "number_fallback",
            "n1 numeric");
    PreparedStatement psinsert = con.prepareStatement("insert into number_fallback values(?)");
    PreparedStatement psselect = con.prepareStatement("select n1 from number_fallback");

    psinsert.setObject(1, new BigInteger("733"));
    psinsert.execute();

    ResultSet rs = psselect.executeQuery();
    assertTrue(rs.next());
    assertTrue(
        "expected 733, but received " + rs.getBigDecimal(1),
        new BigDecimal("733").compareTo(rs.getBigDecimal(1)) == 0);

    psinsert.close();
    psselect.close();
  }

  @Test
  public void testSetObjectNumberFallbackWithAtomicLong() throws SQLException {
    TestUtil.createTempTable(con, "number_fallback",
            "n1 numeric");
    PreparedStatement psinsert = con.prepareStatement("insert into number_fallback values(?)");
    PreparedStatement psselect = con.prepareStatement("select n1 from number_fallback");

    psinsert.setObject(1, new AtomicLong(733));
    psinsert.execute();

    ResultSet rs = psselect.executeQuery();
    assertTrue(rs.next());
    assertTrue(
        "expected 733, but received " + rs.getBigDecimal(1),
        new BigDecimal("733").compareTo(rs.getBigDecimal(1)) == 0);

    psinsert.close();
    psselect.close();
  }

  @Test
  public void testUnknownSetObject() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO intervaltable(i) VALUES (?)");

    pstmt.setString(1, "1 week");
    try {
      pstmt.executeUpdate();
      assertTrue("When using extended protocol, interval vs character varying type mismatch error is expected",
          preferQueryMode == PreferQueryMode.SIMPLE);
    } catch (SQLException sqle) {
      // ERROR: column "i" is of type interval but expression is of type character varying
    }

    pstmt.setObject(1, "1 week", Types.OTHER);
    pstmt.executeUpdate();
    pstmt.close();
  }

  /**
   * With autoboxing this apparently happens more often now.
   */
  @Test
  public void testSetObjectCharacter() throws SQLException {
    PreparedStatement ps = con.prepareStatement("INSERT INTO texttable(te) VALUES (?)");
    ps.setObject(1, 'z');
    ps.executeUpdate();
    ps.close();
  }

  /**
   * When we have parameters of unknown type and it's not using the unnamed statement, we issue a
   * protocol level statement describe message for the V3 protocol. This test just makes sure that
   * works.
   */
  @Test
  public void testStatementDescribe() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int");
    pstmt.setObject(1, 2, Types.OTHER);
    for (int i = 0; i < 10; i++) {
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      rs.close();
    }
    pstmt.close();
  }

  @Test
  public void testBatchWithPrepareThreshold5() throws SQLException {
    assumeBinaryModeRegular();
    Assume.assumeTrue("simple protocol only does not support prepared statement requests",
        preferQueryMode != PreferQueryMode.SIMPLE);

    PreparedStatement pstmt = con.prepareStatement("CREATE temp TABLE batch_tab_threshold5 (id bigint, val bigint)");
    pstmt.executeUpdate();
    pstmt.close();

    // When using a prepareThreshold of 5, a batch update should use server-side prepare
    pstmt = con.prepareStatement("INSERT INTO batch_tab_threshold5 (id, val) VALUES (?,?)");
    ((PgStatement) pstmt).setPrepareThreshold(5);
    for (int p = 0; p < 5; p++) {
      for (int i = 0; i <= 5; i++) {
        pstmt.setLong(1, i);
        pstmt.setLong(2, i);
        pstmt.addBatch();
      }
      pstmt.executeBatch();
    }
    pstmt.close();
    Assume.assumeFalse("Test assertions below support only non-rewritten insert statements",
        con.unwrap(PgConnection.class).getQueryExecutor().isReWriteBatchedInsertsEnabled());
    assertTrue("prepareThreshold=5, so the statement should be server-prepared",
        ((PGStatement) pstmt).isUseServerPrepare());
    assertEquals("prepareThreshold=5, so the statement should be server-prepared", 1,
        getNumberOfServerPreparedStatements("INSERT INTO batch_tab_threshold5 (id, val) VALUES ($1,$2)"));
  }

  @Test
  public void testBatchWithPrepareThreshold0() throws SQLException {
    assumeBinaryModeRegular();
    Assume.assumeTrue("simple protocol only does not support prepared statement requests",
        preferQueryMode != PreferQueryMode.SIMPLE);

    PreparedStatement pstmt = con.prepareStatement("CREATE temp TABLE batch_tab_threshold0 (id bigint, val bigint)");
    pstmt.executeUpdate();
    pstmt.close();

    // When using a prepareThreshold of 0, a batch update should not use server-side prepare
    pstmt = con.prepareStatement("INSERT INTO batch_tab_threshold0 (id, val) VALUES (?,?)");
    ((PgStatement) pstmt).setPrepareThreshold(0);
    for (int p = 0; p < 5; p++) {
      for (int i = 0; i <= 5; i++) {
        pstmt.setLong(1, i);
        pstmt.setLong(2, i);
        pstmt.addBatch();
      }
      pstmt.executeBatch();
    }
    pstmt.close();

    assertFalse("prepareThreshold=0, so the statement should not be server-prepared",
        ((PGStatement) pstmt).isUseServerPrepare());
    assertEquals("prepareThreshold=0, so the statement should not be server-prepared", 0,
        getNumberOfServerPreparedStatements("INSERT INTO batch_tab_threshold0 (id, val) VALUES ($1,$2)"));
  }

  @Test
  public void testSelectPrepareThreshold0AutoCommitFalseFetchSizeNonZero() throws SQLException {
    assumeBinaryModeRegular();
    Assume.assumeTrue("simple protocol only does not support prepared statement requests",
        preferQueryMode != PreferQueryMode.SIMPLE);

    con.setAutoCommit(false);
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    try {
      pstmt = con.prepareStatement("SELECT 42");
      ((PgStatement) pstmt).setPrepareThreshold(0);
      pstmt.setFetchSize(1);
      rs = pstmt.executeQuery();
      rs.next();
      assertEquals(42, rs.getInt(1));
    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(pstmt);
    }

    assertFalse("prepareThreshold=0, so the statement should not be server-prepared",
        ((PGStatement) pstmt).isUseServerPrepare());

    assertEquals("prepareThreshold=0, so the statement should not be server-prepared", 0,
        getNumberOfServerPreparedStatements("SELECT 42"));
  }

  @Test
  public void testInappropriateStatementSharing() throws SQLException {
    PreparedStatement ps = con.prepareStatement("SELECT ?::timestamp");
    assertFirstParameterTypeName("after prepare ?::timestamp bind type should be timestamp", "timestamp", ps);
    try {
      Timestamp ts = new Timestamp(1474997614836L);
      // Since PreparedStatement isn't cached immediately, we need to some warm up
      for (int i = 0; i < 3; i++) {
        ResultSet rs;

        // Flip statement to use Oid.DATE
        ps.setNull(1, Types.DATE);
        assertFirstParameterTypeName("set parameter to DATE", "date", ps);
        rs = ps.executeQuery();
        assertFirstParameterTypeName("set parameter to DATE (executeQuery should not affect parameterMetadata)",
            "date", ps);
        try {
          assertTrue(rs.next());
          assertNull("NULL DATE converted to TIMESTAMP should return NULL value on getObject",
              rs.getObject(1));
        } finally {
          rs.close();
        }

        // Flop statement to use Oid.UNSPECIFIED
        ps.setTimestamp(1, ts);
        assertFirstParameterTypeName("set parameter to Timestamp", "timestamp", ps);
        rs = ps.executeQuery();
        assertFirstParameterTypeName("set parameter to Timestamp (executeQuery should not affect parameterMetadata)",
            "timestamp", ps);
        try {
          assertTrue(rs.next());
          assertEquals(
              "Looks like we got a narrowing of the data (TIMESTAMP -> DATE). It might caused by inappropriate caching of the statement.",
              ts, rs.getObject(1));
        } finally {
          rs.close();
        }
      }
    } finally {
      ps.close();
    }
  }

  private void assertFirstParameterTypeName(String msg, String expected, PreparedStatement ps) throws SQLException {
    if (preferQueryMode == PreferQueryMode.SIMPLE) {
      return;
    }
    ParameterMetaData pmd = ps.getParameterMetaData();
    assertEquals("getParameterMetaData().getParameterTypeName(1) " + msg,
        expected, pmd.getParameterTypeName(1));
  }

  @Test
  public void testAlternatingBindType() throws SQLException {
    assumeBinaryModeForce();
    PreparedStatement ps = con.prepareStatement("SELECT /*testAlternatingBindType*/ ?");
    ResultSet rs;
    Logger log = Logger.getLogger("org.postgresql.core.v3.SimpleQuery");
    Level prevLevel = log.getLevel();
    if (prevLevel == null || prevLevel.intValue() > Level.FINER.intValue()) {
      log.setLevel(Level.FINER);
    }
    final AtomicInteger numOfReParses = new AtomicInteger();
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (record.getMessage().contains("un-prepare it and parse")) {
          numOfReParses.incrementAndGet();
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() throws SecurityException {
      }
    };
    log.addHandler(handler);
    try {
      ps.setString(1, "42");
      rs = ps.executeQuery();
      rs.next();
      Assert.assertEquals("setString(1, \"42\") -> \"42\" expected", "42", rs.getObject(1));
      rs.close();

      // The bind type is flipped from VARCHAR to INTEGER, and it causes the driver to prepare statement again
      ps.setNull(1, Types.INTEGER);
      rs = ps.executeQuery();
      rs.next();
      Assert.assertNull("setNull(1, Types.INTEGER) -> null expected", rs.getObject(1));
      Assert.assertEquals("A re-parse was expected, so the number of parses should be 1",
          1, numOfReParses.get());
      rs.close();

      // The bind type is flipped from INTEGER to VARCHAR, and it causes the driver to prepare statement again
      ps.setString(1, "42");
      rs = ps.executeQuery();
      rs.next();
      Assert.assertEquals("setString(1, \"42\") -> \"42\" expected", "42", rs.getObject(1));
      Assert.assertEquals("One more re-parse is expected, so the number of parses should be 2",
          2, numOfReParses.get());
      rs.close();

      // Types.OTHER null is sent as UNSPECIFIED, and pgjdbc does not re-parse on UNSPECIFIED nulls
      // Note: do not rely on absence of re-parse on using Types.OTHER. Try using consistent data types
      ps.setNull(1, Types.OTHER);
      rs = ps.executeQuery();
      rs.next();
      Assert.assertNull("setNull(1, Types.OTHER) -> null expected", rs.getObject(1));
      Assert.assertEquals("setNull(, Types.OTHER) should not cause re-parse",
          2, numOfReParses.get());

      // Types.INTEGER null is sent as int4 null, and it leads to re-parse
      ps.setNull(1, Types.INTEGER);
      rs = ps.executeQuery();
      rs.next();
      Assert.assertNull("setNull(1, Types.INTEGER) -> null expected", rs.getObject(1));
      Assert.assertEquals("setNull(, Types.INTEGER) causes re-parse",
          3, numOfReParses.get());
      rs.close();
    } finally {
      TestUtil.closeQuietly(ps);
      log.removeHandler(handler);
      log.setLevel(prevLevel);
    }
  }

  @Test
  public void testNoParametersNPE() throws SQLException {
    try {
      PreparedStatement ps = con.prepareStatement("select 1");
      ps.setString(1, "null");
    } catch ( NullPointerException ex ) {
      fail("Should throw a SQLException");
    } catch (SQLException ex) {
      // ignore
    }
  }
}
