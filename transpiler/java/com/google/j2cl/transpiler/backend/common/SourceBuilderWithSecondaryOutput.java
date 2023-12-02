package com.google.j2cl.transpiler.backend.common;

public final class SourceBuilderWithSecondaryOutput extends SourceBuilder {
  @FunctionalInterface
  public interface Output {
    void append(String s);
  }

  private final Output secondaryOutput;
  private int enabled = 0;

  public SourceBuilderWithSecondaryOutput(Output secondaryOutput, boolean enabled) {
    super();
    this.secondaryOutput = secondaryOutput;
    this.enabled = enabled ? 1 : 0;
  }

  public boolean isSecondaryEnabled() {
    return enabled > 0;
  }

  @Override
  public void append(String source) {
    super.append(source);
    if (enabled > 0) {
      secondaryOutput.append(source);
    }
  }

  public void appendToSecondary(String s) {
    secondaryOutput.append(s);
  }

  public void enableSecondaryOutput(boolean println) {
    if (println && enabled == 0) {
      secondaryOutput.append("\n");
    }
    ++this.enabled;
  }

  public void disableSecondaryOutput(boolean println) {
    if (println && enabled > 0) {
      secondaryOutput.append("\n");
    }
    --this.enabled;
  }
}
