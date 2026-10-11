// Standalone JDK source-file diagnostic; never compiled into the SUT artifact.
class ClockResolution {
  public static void main(String[] args) {
    if (args.length != 3) throw new IllegalArgumentException("sampling-settings-required");
    int samples = Integer.parseInt(args[0]), warmup = Integer.parseInt(args[1]);
    long budgetMs = Long.parseLong(args[2]);
    if (samples < 1 || samples > 100000 || warmup < 0 || warmup > 100000 || budgetMs < 1 || budgetMs > 10000)
      throw new IllegalArgumentException("invalid-sampling-settings");
    long[] deltas = new long[samples];
    long started = System.currentTimeMillis(), previous = System.nanoTime();
    int collected = 0;
    boolean timedOut = false;
    for (int index = 0; index < warmup + samples; index++) {
      if (index % 256 == 0 && System.currentTimeMillis() - started >= budgetMs) { timedOut = true; break; }
      long next = System.nanoTime();
      if (index >= warmup) deltas[collected++] = next - previous;
      previous = next;
    }
    String version = System.getProperty("java.version").replaceAll("[^A-Za-z0-9._+\\-]", "?");
    StringBuilder output = new StringBuilder(256 + samples * 8);
    output.append("{\"clock\":\"System.nanoTime\",\"unit\":\"nanoseconds\",\"runtimeVersion\":\"")
      .append(version).append("\",\"timedOut\":").append(timedOut).append(",\"deltas\":[");
    for (int index = 0; index < collected; index++) { if (index > 0) output.append(','); output.append(deltas[index]); }
    System.out.println(output.append("]}"));
  }
}
