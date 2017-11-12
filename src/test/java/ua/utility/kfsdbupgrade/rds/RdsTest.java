package ua.utility.kfsdbupgrade.rds;

import org.junit.Test;

import com.amazonaws.services.rds.AmazonRDS;

public final class RdsTest {

  @Test
  public void test() {
    try {
      String region = "us-west-2";
      String snapshotDatabase = "kfs3imp";
      String instanceId = "kfs36014";
      AmazonRDS rds = new AmazonRdsProvider(region).get();
      String snapshotId = new LatestSnapshotProvider(rds, snapshotDatabase, true).get();
      new DeleteDatabaseProvider(rds, instanceId).get();
      new CreateDatabaseProvider(rds, instanceId, snapshotId).get();
      new HardenDatabaseProvider(rds, instanceId).get();
      new RebootDatabaseProvider(rds, instanceId).get();
    } catch (Throwable e) {
      e.printStackTrace();
      throw new IllegalStateException(e);
    }
  }

}
