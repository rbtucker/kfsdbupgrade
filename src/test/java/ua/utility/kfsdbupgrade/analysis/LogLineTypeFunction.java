package ua.utility.kfsdbupgrade.analysis;

import com.google.common.base.Function;

public enum LogLineTypeFunction implements Function<String, LogLineType> {
  INSTANCE;

  public LogLineType apply(String input) {

    if (input.contains("INFO")) {
      return LogLineType.INFO;
    }

    if (input.contains("WARN") || input.contains("WARNING")) {
      return LogLineType.WARNING;
    }

    if (input.contains("ERROR")) {
      return LogLineType.ERROR;
    }

    return LogLineType.OTHER;
  }

}
