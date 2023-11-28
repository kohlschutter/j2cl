package com.google.j2cl.transpiler.backend.common;

public final class SourceBuilderWithSecondaryOutput extends SourceBuilder {
  @FunctionalInterface
  public interface Output {
    void append(String s);
  }

  private final Output secondaryOutput;
  private boolean enabled = false;

  public SourceBuilderWithSecondaryOutput(Output secondaryOutput, boolean enabled) {
    super();
    this.secondaryOutput = secondaryOutput;
    this.enabled = enabled;
  }

  @Override
  public void append(String source) {
    super.append(source);
    if (enabled) {
      secondaryOutput.append(source);
    }
  }

  public void enableSecondaryOutput(boolean println) {
    if (println && !enabled) {
      secondaryOutput.append("\n");
    }
    this.enabled = true;
  }

  public void disableSecondaryOutput(boolean println) {
    if (println && enabled) {
      secondaryOutput.append("\n");
    }
    this.enabled = false;
  }
}
