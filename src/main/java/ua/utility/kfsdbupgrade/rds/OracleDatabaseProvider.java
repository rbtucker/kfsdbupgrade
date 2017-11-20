package ua.utility.kfsdbupgrade.rds;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Stopwatch.createStarted;
import static java.util.Arrays.asList;
import static org.apache.log4j.Logger.getLogger;
import static ua.utility.kfsdbupgrade.log.Logging.info;
import static ua.utility.kfsdbupgrade.md.base.Formats.getTime;
import static ua.utility.kfsdbupgrade.md.base.Props.checkedValue;
import static ua.utility.kfsdbupgrade.md.base.Props.parseBoolean;
import static ua.utility.kfsdbupgrade.rds.Rds.DEFAULT_AWS_REGION;
import static ua.utility.kfsdbupgrade.rds.Rds.DEFAULT_ORACLE_SID;
import static ua.utility.kfsdbupgrade.rds.Rds.checkPresent;
import static ua.utility.kfsdbupgrade.rds.Rds.checkedName;
import static ua.utility.kfsdbupgrade.rds.Rds.checkedSid;
import static ua.utility.kfsdbupgrade.rds.Rds.getNormalizedName;
import static ua.utility.kfsdbupgrade.rds.Rds.getNormalizedSid;

import java.util.Properties;

import javax.inject.Provider;

import org.apache.log4j.Logger;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.rds.AmazonRDS;
import com.amazonaws.services.rds.model.DBInstance;
import com.google.common.base.Stopwatch;

public final class OracleDatabaseProvider implements Provider<OracleDatabase> {

  private static final Logger LOGGER = getLogger(OracleDatabaseProvider.class);

  public OracleDatabaseProvider(Properties props) {
    this.props = checkNotNull(props);
  }

  private final Properties props;

  @Override
  public OracleDatabase get() {
    Stopwatch sw = createStarted();
    String region = checkedValue(props, asList("aws.region", "AWS_DEFAULT_REGION"), DEFAULT_AWS_REGION);
    String snapshotDatabase = checkedName(getNormalizedName(checkedValue(props, "db.snapshot.name")));
    String name = checkedName(getNormalizedName(checkedValue(props, "db.name")));
    String sid = checkedSid(getNormalizedSid(props.getProperty("db.sid", DEFAULT_ORACLE_SID)));
    AWSCredentials credentials = new CredentialsProvider(props).get();
    AmazonRDS rds = new AmazonRdsProvider(region, credentials).get();
    if (parseBoolean(props, "db.create", false)) {
      info(LOGGER, "provisioning new database [%s] oracle sid: %s", name, sid);
      boolean automatedOnly = parseBoolean(props, "rds.snapshot.automated.only", true);
      String snapshotId = new LatestSnapshotProvider(rds, snapshotDatabase, automatedOnly).get();
      new DeleteDatabaseProvider(rds, name, props).get();
      new CreateDatabaseProvider(rds, name, sid, snapshotId, props).get();
      new FinalizeDatabaseProvider(rds, name, props).get();
      new RebootDatabaseProvider(rds, name, props).get();
    } else {
      checkPresent(rds, name);
    }
    DBInstance aws = new DatabaseInstanceProvider(rds, name).get().get();
    OracleDatabase oracle = OracleDatabaseFunction.INSTANCE.apply(aws);
    checkState(oracle.getSid().equals(sid), "Oracle SID mismatch :: [expected=%s, actual=%s]", sid, oracle.getSid());
    info(LOGGER, "region ---> %s", region);
    info(LOGGER, "name -----> %s", name);
    info(LOGGER, "endpoint -> %s", oracle.getEndpoint());
    info(LOGGER, "port -----> %s", oracle.getPort());
    info(LOGGER, "SID ------> %s", oracle.getSid());
    info(LOGGER, "jdbc -----> %s", OracleJdbcUrlFunction.INSTANCE.apply(oracle));
    info(LOGGER, "database [%s] is %s - [%s]", name, aws.getDBInstanceStatus(), getTime(sw));
    return oracle;
  }

}
