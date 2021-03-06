package ua.utility.kfsdbupgrade.md;

import static com.google.common.base.Optional.absent;
import static com.google.common.base.Optional.of;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Stopwatch.createStarted;
import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.log4j.Logger.getLogger;
import static ua.utility.kfsdbupgrade.log.Logging.info;
import static ua.utility.kfsdbupgrade.md.Closeables.closeQuietly;
import static ua.utility.kfsdbupgrade.md.base.Formats.getCount;
import static ua.utility.kfsdbupgrade.md.base.Formats.getThroughputInSeconds;
import static ua.utility.kfsdbupgrade.md.base.Formats.getTime;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import javax.inject.Provider;

import org.apache.log4j.Logger;

import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;

public final class RowIdProvider implements Provider<ImmutableList<String>> {

  private static final Logger LOGGER = getLogger(RowIdProvider.class);

  private final Connection conn;
  private final Optional<String> schema;
  private final String table;
  private final Optional<Integer> max;
  private final Optional<Integer> show;

  @Override
  public ImmutableList<String> get() {
    List<String> list = newArrayList();
    Statement stmt = null;
    ResultSet rs = null;
    try {
      Stopwatch sw = createStarted();
      String from = schema.isPresent() ? schema.get() + "." + table : table;
      String acquire = max.isPresent() ? "maximum of " + getCount(max.get()) : "all";
      info(LOGGER, "acquiring %s row ids from %s", acquire, from);
      stmt = conn.createStatement();
      rs = stmt.executeQuery(format("SELECT ROWID FROM %s", from));
      while (rs.next()) {
        list.add(rs.getString(1));
        if (show.isPresent() && list.size() % show.get() == 0) {
          info(LOGGER, "%s", getCount(list.size()));
        }
        if (max.isPresent() && list.size() == max.get()) {
          break;
        }
      }
      String tp = getThroughputInSeconds(sw.elapsed(MILLISECONDS), list.size(), "row ids/sec");
      info(LOGGER, "acquired %s row ids from %s in %s [%s]", getCount(list.size()), from, getTime(sw), tp);
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    } finally {
      closeQuietly(rs);
      closeQuietly(stmt);
    }
    return copyOf(list);
  }

  private RowIdProvider(Builder builder) {
    this.conn = builder.conn;
    this.max = builder.max;
    this.show = builder.show;
    this.schema = builder.schema;
    this.table = builder.table;
  }

  public static RowIdProvider build(Connection conn, String table, int max, int show) {
    Builder builder = builder();
    builder.withConn(conn);
    builder.withTable(table);
    builder.withMax(max);
    builder.withShow(show);
    return builder.build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private Connection conn;
    private Optional<String> schema = absent();
    private String table;
    private Optional<Integer> max = absent();
    private Optional<Integer> show = of(100000);

    public Builder withConn(Connection conn) {
      this.conn = conn;
      return this;
    }

    public Builder withTable(String table) {
      this.table = table;
      return this;
    }

    public Builder withSchema(String schema) {
      return withSchema(of(schema));
    }

    public Builder withSchema(Optional<String> schema) {
      this.schema = schema;
      return this;
    }

    public Builder withMax(int max) {
      return withMax(of(max));
    }

    public Builder withMax(Optional<Integer> max) {
      this.max = max;
      return this;
    }

    public Builder withShow(int show) {
      return withShow(of(show));
    }

    public Builder withShow(Optional<Integer> show) {
      this.show = show;
      return this;
    }

    public RowIdProvider build() {
      return validate(new RowIdProvider(this));
    }

    private static RowIdProvider validate(RowIdProvider instance) {
      checkNotNull(instance.conn, "conn may not be null");
      checkNotNull(instance.schema, "schema may not be null");
      checkNotNull(instance.table, "table may not be null");
      checkNotNull(instance.max, "max may not be null");
      checkNotNull(instance.show, "show may not be null");
      return instance;
    }
  }

}
