package ua.utility.kfsdbupgrade.md;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Stopwatch.createStarted;
import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.log4j.Logger.getLogger;
import static ua.utility.kfsdbupgrade.log.Logging.info;
import static ua.utility.kfsdbupgrade.md.Closeables.closeQuietly;
import static ua.utility.kfsdbupgrade.md.base.Formats.getTime;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import javax.inject.Provider;

import org.apache.log4j.Logger;

import com.google.common.base.Stopwatch;

public final class ConnectionProvider implements Provider<Connection> {

  private static final Logger LOGGER = getLogger(ConnectionProvider.class);

  public ConnectionProvider(Properties props, boolean autoCommit) {
    this.props = checkNotNull(props);
    this.autoCommit = autoCommit;
  }

  private final Properties props;
  private final boolean autoCommit;

  public Connection get() {
    Connection connection = null;
    try {
      String username = checkedValue(props, "database-user");
      String password = checkedValue(props, "database-password");
      String url;
      if (props.containsKey("db.name")) {
        String name = checkedValue(props, "db.name");
        info(LOGGER, "using database name '%s' to construct full jdbc url", name, username);
        String fragment = checkedValue(props, "db.fragment");
        String port = props.getProperty("db.port", "1521");
        String sid = props.getProperty("db.sid", name);
        String formatted = format("jdbc:oracle:thin:@%s.%s:%s:%s", name.toLowerCase(), fragment, port, sid.toUpperCase());
        url = formatted;
      } else {
        if (props.containsKey("db.url")) {
          url = props.getProperty("db.url");
        } else {
          url = checkedValue(props, "database-url");
        }
      }
      Stopwatch sw = createStarted();
      info(LOGGER, "%s as '%s'", url, username);
      Class.forName(checkedValue(props, "database-driver"));
      connection = DriverManager.getConnection(url, username, password);
      connection.setReadOnly(false);
      connection.setAutoCommit(autoCommit);
      info(LOGGER, "%s as '%s' [%s]", url, username, getTime(sw));
      return connection;
    } catch (Throwable e) {
      closeQuietly(connection);
      throw new IllegalStateException(e);
    }
  }

  private String checkedValue(Properties props, String key) {
    String value = props.getProperty(key);
    checkArgument(isNotBlank(value), "%s cannot be blank", key);
    return value;
  }

}
