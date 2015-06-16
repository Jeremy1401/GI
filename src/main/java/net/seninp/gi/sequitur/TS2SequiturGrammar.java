package net.seninp.gi.sequitur;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import net.seninp.gi.GrammarRuleRecord;
import net.seninp.gi.GrammarRules;
import net.seninp.gi.RuleInterval;
import net.seninp.jmotif.sax.NumerosityReductionStrategy;
import net.seninp.jmotif.sax.SAXProcessor;
import net.seninp.jmotif.sax.TSProcessor;
import net.seninp.jmotif.sax.alphabet.Alphabet;
import net.seninp.jmotif.sax.alphabet.NormalAlphabet;
import net.seninp.jmotif.sax.datastructures.SAXRecords;
import net.seninp.util.StackTrace;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

public class TS2SequiturGrammar {

  private final static Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

  private static final double NORMALIZATION_THRESHOLD = 0.01;

  private static final NumerosityReductionStrategy numerosityReductionStrategy = NumerosityReductionStrategy.EXACT;

  private static final String CR = "\n";

  private static TSProcessor tp = new TSProcessor();
  private static SAXProcessor sp = new SAXProcessor();

  /** The data filename. */
  private static String dataFileName;

  // the logger business
  //
  private static Logger consoleLogger;
  private static Level LOGGING_LEVEL = Level.DEBUG;

  // private static SAXRecords saxFrequencyData;

  private static double[] originalTimeSeries;

  private static Integer saxWindowSize;

  private static Integer saxPaaSize;

  private static Integer saxAlphabetSize;

  private static Alphabet normalA = new NormalAlphabet();

  private static String outputPrefix;

  static {
    consoleLogger = (Logger) LoggerFactory.getLogger(TS2SequiturGrammar.class);
    consoleLogger.setLevel(LOGGING_LEVEL);
  }

  public static void main(String[] args) {

    if (args.length == 5) {
      try {
        consoleLogger.info("Parsing param string \"" + Arrays.toString(args) + "\"");

        dataFileName = args[0];
        originalTimeSeries = loadData(dataFileName);

        saxWindowSize = Integer.valueOf(args[1]);
        saxPaaSize = Integer.valueOf(args[2]);
        saxAlphabetSize = Integer.valueOf(args[3]);

        outputPrefix = args[4];

        consoleLogger.info("Starting conversion " + dataFileName + " with settings: window "
            + saxWindowSize + ", paa " + saxPaaSize + ", alphabet " + saxAlphabetSize
            + ", out prefix " + outputPrefix);

      }
      catch (Exception e) {
        System.err.println("error occured while parsing parameters:\n" + StackTrace.toString(e));
        System.exit(-1);
      }
      // end parsing brute-force parameters
      //
    }
    else {
      System.err.println("expected 5 parameters");
    }

    SAXRecords saxFrequencyData = new SAXRecords();

    SAXRule.numRules = new AtomicInteger(0);
    SAXSymbol.theDigrams.clear();
    SAXSymbol.theSubstituteTable.clear();
    SAXRule.arrRuleRecords = new ArrayList<GrammarRuleRecord>();

    SAXRule grammar = new SAXRule();

    Date fullStart = new Date();

    try {

      String previousString = "";

      // scan across the time series extract sub sequences, and convert
      // them to strings
      int stringPosCounter = 0;
      for (int i = 0; i < originalTimeSeries.length - (saxWindowSize - 1); i++) {

        // fix the current subsection
        double[] subSection = Arrays.copyOfRange(originalTimeSeries, i, i + saxWindowSize);

        // Z normalize it
        if (tp.stDev(subSection) > NORMALIZATION_THRESHOLD) {
          subSection = tp.znorm(subSection, NORMALIZATION_THRESHOLD);
        }

        // perform PAA conversion if needed
        double[] paa = tp.paa(subSection, saxPaaSize);

        // Convert the PAA to a string.
        char[] currentString = tp.ts2String(paa, normalA.getCuts(saxAlphabetSize));

        // NumerosityReduction
        if (!previousString.isEmpty()) {

          if ((NumerosityReductionStrategy.MINDIST == numerosityReductionStrategy)
              && (0.0 == sp.saxMinDist(previousString.toCharArray(), currentString,
                  normalA.getDistanceMatrix(saxAlphabetSize)))) {
            continue;
          }
          else if ((NumerosityReductionStrategy.EXACT == numerosityReductionStrategy)
              && previousString.equalsIgnoreCase(new String(currentString))) {
            continue;
          }
        }

        previousString = new String(currentString);

        grammar.last().insertAfter(new SAXTerminal(new String(currentString), stringPosCounter));
        grammar.last().p.check();

        saxFrequencyData.add(currentString, i);

        stringPosCounter++;

      }

      // String res = saxFrequencyData.getSAXString(" ");
      // res = null;

      Date end = new Date();

      System.out.println("Discretized and inferred grammar with Sequitur in  "
          + SAXProcessor.timeToString(fullStart.getTime(), end.getTime()));

      Date start = new Date();
      GrammarRules rules = grammar.toGrammarRulesData();

      SequiturFactory.updateRuleIntervals(rules, saxFrequencyData, true, originalTimeSeries,
          saxWindowSize, saxPaaSize);
      end = new Date();

      System.out.println("Expanded rules and computed intervals  in  "
          + String.valueOf(end.getTime() - start.getTime()) + " ms, "
          + SAXProcessor.timeToString(start.getTime(), end.getTime()));

      start = new Date();

      int[] coverageArray = new int[originalTimeSeries.length];

      for (GrammarRuleRecord r : rules) {
        if (0 == r.ruleNumber()) {
          continue;
        }
        ArrayList<RuleInterval> arrPos = r.getRuleIntervals();
        for (RuleInterval saxPos : arrPos) {
          int startPos = saxPos.getStartPos();
          int endPos = saxPos.getEndPos();
          for (int j = startPos; j < endPos; j++) {
            coverageArray[j] = coverageArray[j] + 1;
          }
        }
      }
      end = new Date();
      System.out.println("Computed rule coverage in  "
          + String.valueOf(end.getTime() - start.getTime()) + " ms, "
          + SAXProcessor.timeToString(start.getTime(), end.getTime()));

      System.out.println("Total runtime  " + String.valueOf(end.getTime() - fullStart.getTime())
          + " ms, " + SAXProcessor.timeToString(fullStart.getTime(), end.getTime()));

      // write down the coverage array
      //
      String currentPath = new File(".").getCanonicalPath();
      BufferedWriter bw = new BufferedWriter(new FileWriter(new File(currentPath + File.separator
          + outputPrefix + "_SEQUITUR_density_curve.txt")));
      for (int c : coverageArray) {
        bw.write(String.valueOf(c) + "\n");
      }
      bw.close();

    }
    catch (Exception ex) {
      ex.printStackTrace();
    }

  }

  @SuppressWarnings("unused")
  private static String collectMotifStats(SAXRule grammar) throws IOException {

    // start collecting stats
    //
    consoleLogger.info("Collecting stats:");
    String currentPath = new File(".").getCanonicalPath();
    String fname = currentPath + File.separator + outputPrefix + "_sequitur_grammar_stat.txt";

    boolean fileOpen = false;
    BufferedWriter bw = null;
    try {
      bw = new BufferedWriter(new FileWriter(new File(fname)));
      StringBuffer sb = new StringBuffer();
      sb.append("# filename: ").append(fname).append(CR);
      sb.append("# sliding window: ").append(saxWindowSize).append(CR);
      sb.append("# paa size: ").append(saxPaaSize).append(CR);
      sb.append("# alphabet size: ").append(saxAlphabetSize).append(CR);
      bw.write(sb.toString());
      fileOpen = true;
    }
    catch (IOException e) {
      System.err.print("Encountered an error while writing stats file: \n" + StackTrace.toString(e)
          + "\n");
    }

    GrammarRules rules = grammar.toGrammarRulesData();

    // SequiturFactory.updateRuleIntervals(rules, saxFrequencyData, true, originalTimeSeries,
    // saxWindowSize, saxPaaSize);

    for (GrammarRuleRecord ruleRecord : rules) {

      StringBuffer sb = new StringBuffer();
      sb.append("/// ").append(ruleRecord.getRuleName()).append(CR);
      sb.append(ruleRecord.getRuleName()).append(" -> \'")
          .append(ruleRecord.getRuleString().trim()).append("\', expanded rule string: \'")
          .append(ruleRecord.getExpandedRuleString()).append("\'").append(CR);

      if (!ruleRecord.getOccurrences().isEmpty()) {
        sb.append("subsequences starts: ")
            .append(
                Arrays.toString(ruleRecord.getOccurrences().toArray(
                    new Integer[ruleRecord.getOccurrences().size()]))).append(CR);
        int[] lengths = new int[ruleRecord.getRuleIntervals().size()];
        int i = 0;
        for (RuleInterval r : ruleRecord.getRuleIntervals()) {
          lengths[i] = r.getEndPos() - r.getStartPos();
          i++;
        }
        sb.append("subsequences lengths: ").append(Arrays.toString(lengths)).append(CR);
      }

      sb.append("rule occurrence frequency ").append(ruleRecord.getOccurrences().size()).append(CR);
      sb.append("rule use frequency ").append(ruleRecord.getRuleUseFrequency()).append(CR);
      sb.append("min length ").append(ruleRecord.minMaxLengthAsString().split(" - ")[0]).append(CR);
      sb.append("max length ").append(ruleRecord.minMaxLengthAsString().split(" - ")[1]).append(CR);
      sb.append("mean length ").append(ruleRecord.getMeanLength()).append(CR);

      if (fileOpen) {
        try {
          bw.write(sb.toString());
        }
        catch (IOException e) {
          System.err.print("Encountered an error while writing stats file: \n"
              + StackTrace.toString(e) + "\n");
        }
      }
    }

    // try to write stats into the file
    if (fileOpen) {
      try {
        bw.close();
      }
      catch (IOException e) {
        System.err.print("Encountered an error while writing stats file: \n"
            + StackTrace.toString(e) + "\n");
      }
    }

    return null;
  }

  /**
   * This reads the data
   * 
   * @param fname The filename.
   * @return
   */
  private static double[] loadData(String fname) {

    consoleLogger.info("reading from " + fname);

    long lineCounter = 0;
    double ts[] = new double[1];

    Path path = Paths.get(fname);

    ArrayList<Double> data = new ArrayList<Double>();

    try {

      BufferedReader reader = Files.newBufferedReader(path, DEFAULT_CHARSET);

      String line = null;
      while ((line = reader.readLine()) != null) {
        String[] lineSplit = line.trim().split("\\s+");
        for (int i = 0; i < lineSplit.length; i++) {
          double value = new BigDecimal(lineSplit[i]).doubleValue();
          data.add(value);
        }
        lineCounter++;
      }
      reader.close();
    }
    catch (Exception e) {
      System.err.println(StackTrace.toString(e));
    }
    finally {
      assert true;
    }

    if (!(data.isEmpty())) {
      ts = new double[data.size()];
      for (int i = 0; i < data.size(); i++) {
        ts[i] = data.get(i);
      }
    }

    consoleLogger.info("loaded " + data.size() + " points from " + lineCounter + " lines in "
        + fname);
    return ts;

  }

}
