package ua.utility.kfsdbupgrade.md;

import static ua.utility.kfsdbupgrade.md.base.Exceptions.illegalState;

import java.sql.ResultSet;
import java.sql.SQLException;

import com.google.common.base.Function;

public enum SingleStringFunction implements Function<ResultSet, String> {
  INSTANCE;

  public String apply(ResultSet input) {
    try {
      return input.getString(1);
    } catch (SQLException e) {
      throw illegalState(e);
    }
  }

}
