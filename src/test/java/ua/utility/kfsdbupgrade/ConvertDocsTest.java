package ua.utility.kfsdbupgrade;

import static com.google.common.base.Functions.identity;
import static com.google.common.base.Stopwatch.createUnstarted;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.partition;
import static java.util.Arrays.asList;
import static ua.utility.kfsdbupgrade.mdoc.Callables.fromProvider;
import static ua.utility.kfsdbupgrade.mdoc.Lists.distribute;
import static ua.utility.kfsdbupgrade.mdoc.Lists.newList;
import static ua.utility.kfsdbupgrade.mdoc.Lists.shuffle;
import static ua.utility.kfsdbupgrade.mdoc.Lists.transform;
import static ua.utility.kfsdbupgrade.mdoc.Providers.of;

import java.sql.Connection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import javax.inject.Provider;

import org.junit.Test;

import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;

import ua.utility.kfsdbupgrade.mdoc.ConnectionProvider;
import ua.utility.kfsdbupgrade.mdoc.DataMetrics;
import ua.utility.kfsdbupgrade.mdoc.ExecutorProvider;
import ua.utility.kfsdbupgrade.mdoc.MaintDoc;
import ua.utility.kfsdbupgrade.mdoc.PropertiesProvider;
import ua.utility.kfsdbupgrade.mdoc.RowId;
import ua.utility.kfsdbupgrade.mdoc.RowIdConverter;
import ua.utility.kfsdbupgrade.mdoc.RowSelector;
import ua.utility.kfsdbupgrade.mdoc.RowUpdateProvider;
import ua.utility.kfsdbupgrade.mdoc.SingleStringFunction;
import ua.utility.kfsdbupgrade.mdoc.StringWeigher;
import ua.utility.kfsdbupgrade.mdoc.ThreadsProvider;

public class ConvertDocsTest {

  @Test
  public void test() {
    try {
      Properties props = new PropertiesProvider().get();
      int threads = new ThreadsProvider(props).get();
      ExecutorService executor = new ExecutorProvider("mdoc", threads).get();
      String table = "KRNS_MAINT_DOC_T";
      int max = 10000;
      int show = max / 10;
      int batchSize = 75;
      List<RowId> ids = getRowIds(props, table, max, show);
      DataMetrics overall = new DataMetrics();
      DataMetrics current = new DataMetrics();
      Stopwatch timer = createUnstarted();
      Stopwatch last = createUnstarted();
      RowUpdaterFunction function = new RowUpdaterFunction(show, new DataMetrics(), new DataMetrics(), createUnstarted(), createUnstarted());
      Function<MaintDoc, MaintDoc> converter = identity();
      for (List<RowId> distribution : distribute(ids, threads)) {
        for (List<RowId> partition : partition(distribution, batchSize)) {
          RowSelector<MaintDoc> selector = getSelector(null, partition, overall, current, timer, last, show);
          RowUpdateProvider<MaintDoc> updater = new RowUpdateProvider<>(selector, converter, function);
          Callable<ImmutableList<MaintDoc>> callable = fromProvider(updater);
        }
      }
    } catch (Throwable e) {
      e.printStackTrace();
      throw new IllegalStateException(e);
    }
  }

  // setup selectors that extract maintenance documents
  private static ImmutableList<RowSelector<MaintDoc>> getSelectors(Properties props, List<RowId> ids, int threads, int show) {
    RowIdConverter converter = RowIdConverter.getInstance();
    List<String> rowIds = shuffle(transform(ids, converter.reverse()));
    DataMetrics overall = new DataMetrics();
    DataMetrics current = new DataMetrics();
    Stopwatch timer = createUnstarted();
    Stopwatch last = createUnstarted();
    List<RowSelector<MaintDoc>> list = newArrayList();
    for (List<String> distribution : distribute(rowIds, threads)) {
      Provider<Connection> provider = of(new ConnectionProvider(props, false).get());
      RowSelector.Builder<MaintDoc> builder = RowSelector.builder();
      builder.withFunction(MaintDocFunction.INSTANCE);
      builder.withWeigher(MaintDocWeigher.INSTANCE);
      builder.withRowIds(distribution);
      builder.withShow(show);
      builder.withTable("KRNS_MAINT_DOC_T");
      builder.withProvider(provider);
      builder.withFields(asList("ROWID", "DOC_CNTNT"));
      builder.withOverall(overall);
      builder.withCurrent(current);
      builder.withTimer(timer);
      builder.withLast(last);
      builder.withCloseConnection(true);
      RowSelector<MaintDoc> selector = builder.build();
      list.add(selector);
    }
    return newList(list);
  }

  private ImmutableList<RowId> getRowIds(Properties props, String table, int max, int show) {
    ConnectionProvider provider = new ConnectionProvider(props, false);
    RowSelector.Builder<String> builder = RowSelector.builder();
    builder.withFunction(SingleStringFunction.INSTANCE);
    builder.withWeigher(StringWeigher.INSTANCE);
    builder.withMax(max);
    builder.withShow(show);
    builder.withTable(table);
    builder.withProvider(provider);
    RowSelector<String> selector = builder.build();
    List<String> strings = selector.get();
    return transform(strings, RowIdConverter.getInstance());
  }

}
