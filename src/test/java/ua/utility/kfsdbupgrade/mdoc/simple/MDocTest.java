package ua.utility.kfsdbupgrade.mdoc.simple;

import static com.google.common.base.Stopwatch.createStarted;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.partition;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static ua.utility.kfsdbupgrade.log.Logging.info;
import static ua.utility.kfsdbupgrade.mdoc.Callables.fromProvider;
import static ua.utility.kfsdbupgrade.mdoc.Callables.getFutures;
import static ua.utility.kfsdbupgrade.mdoc.Closeables.closeQuietly;
import static ua.utility.kfsdbupgrade.mdoc.Formats.getCount;
import static ua.utility.kfsdbupgrade.mdoc.Formats.getThroughputInSeconds;
import static ua.utility.kfsdbupgrade.mdoc.Formats.getTime;
import static ua.utility.kfsdbupgrade.mdoc.Lists.concat;
import static ua.utility.kfsdbupgrade.mdoc.Lists.distribute;
import static ua.utility.kfsdbupgrade.mdoc.Providers.fromFunction;

import java.sql.Connection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Provider;

import org.apache.log4j.Logger;
import org.junit.Test;

import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;

import ua.utility.kfsdbupgrade.mdoc.ConnectionProvider;
import ua.utility.kfsdbupgrade.mdoc.ExecutorProvider;
import ua.utility.kfsdbupgrade.mdoc.PropertiesProvider;
import ua.utility.kfsdbupgrade.mdoc.RowId;

public class MDocTest {

  private static final Logger LOGGER = Logger.getLogger(MDocTest.class);

  @Test
  public void test() {
    List<Connection> conns = newArrayList();
    Connection first = null;
    try {
      Properties props = new PropertiesProvider().get();
      ConnectionProvider provider = new ConnectionProvider(props, false);
      first = provider.get();
      MDocContext ctx = new MDocContextProvider(props, first).get();
      ExecutorService rds = new ExecutorProvider("rds", ctx.getRdsThreads()).get();
      ExecutorService ec2 = new ExecutorProvider("ec2", ctx.getEc2Threads()).get();
      conns = new ConnectionsProvider(provider, ctx.getRdsThreads(), first).get();
      List<RowId> rowIds = getRowIds(ctx, conns.iterator().next(), ctx.getMax() / 10);
      Stopwatch overall = createStarted();
      int count = 0;
      for (List<RowId> chunk : partition(rowIds, ctx.getChunkSize())) {
        Stopwatch current = createStarted();
        MDocResult selected = select(rds, conns, chunk, ctx.getSelectSize());
        MDocResult converted = convert(ec2, selected.getDocs(), ctx.getConverter());
        MDocResult stored = store(rds, conns, converted.getDocs(), ctx.getBatchSize());
        String s = getThroughputInSeconds(selected.getMetric().getMillis(), selected.getMetric().getCount(), "docs/sec");
        String c = getThroughputInSeconds(converted.getMetric().getMillis(), converted.getMetric().getCount(), "docs/sec");
        String u = getThroughputInSeconds(stored.getMetric().getMillis(), stored.getMetric().getCount(), "docs/sec");
        count += chunk.size();
        info(LOGGER, "%s select %s, convert %s, store %s [%s %s]", getCount(count), s, c, u, getTime(current), getTime(overall));
      }
    } catch (Throwable e) {
      e.printStackTrace();
      throw new IllegalStateException(e);
    } finally {
      closeQuietly(first);
      closeQuietly(conns);
    }
  }

  private MDocResult select(ExecutorService rds, List<Connection> conns, List<RowId> rows, int selectSize) {
    List<Callable<ImmutableList<MaintDoc>>> callables = newArrayList();
    int index = 0;
    for (List<RowId> distribution : distribute(rows, conns.size())) {
      MDocProvider mdp = new MDocProvider(conns.get(index++), distribution, selectSize);
      callables.add(fromProvider(mdp));
    }
    Stopwatch sw = createStarted();
    List<MaintDoc> docs = concat(getFutures(rds, callables));
    long elapsed = sw.elapsed(TimeUnit.MILLISECONDS);
    DataMetric metric = new DataMetric(docs.size(), sum(docs, false), elapsed);
    return new MDocResult(metric, docs);
  }

  private long sum(Iterable<MaintDoc> docs, boolean contentOnly) {
    long bytes = 0;
    for (MaintDoc doc : docs) {
      if (contentOnly) {
        bytes += doc.getContent().length();
      } else {
        bytes += doc.getRowId().length() + doc.getHeaderId().length() + doc.getContent().length();
      }
    }
    return bytes;
  }

  private MDocResult convert(ExecutorService ec2, List<MaintDoc> docs, Function<MaintDoc, MaintDoc> function) {
    List<Callable<MaintDoc>> callables = newArrayList();
    for (MaintDoc doc : docs) {
      Provider<MaintDoc> provider = fromFunction(doc, function);
      Callable<MaintDoc> callable = fromProvider(provider);
      callables.add(callable);
    }
    Stopwatch sw = createStarted();
    List<MaintDoc> converted = getFutures(ec2, callables);
    long millis = sw.elapsed(MILLISECONDS);
    DataMetric metric = new DataMetric(converted.size(), sum(converted, true), millis);
    return new MDocResult(metric, converted);
  }

  private MDocResult store(ExecutorService rds, List<Connection> conns, List<MaintDoc> docs, int batchSize) {
    int index = 0;
    List<Callable<DataMetric>> callables = newArrayList();
    for (List<MaintDoc> distribution : distribute(docs, conns.size())) {
      MDocUpdater mdu = new MDocUpdater(conns.get(index++), distribution, batchSize);
      callables.add(fromProvider(mdu));
    }
    Stopwatch sw = createStarted();
    getFutures(rds, callables);
    long millis = sw.elapsed(MILLISECONDS);
    DataMetric metric = new DataMetric(docs.size(), sum(docs, true), millis);
    return new MDocResult(metric, docs);
  }

  private ImmutableList<RowId> getRowIds(MDocContext ctx, Connection conn, int show) {
    RowIdProvider.Builder builder = RowIdProvider.builder();
    builder.withConn(conn);
    builder.withMax(ctx.getMax());
    builder.withShow(show);
    builder.withTable(ctx.getTable());
    RowIdProvider provider = builder.build();
    return provider.get();
  }

}
