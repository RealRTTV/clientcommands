package net.earthcomputer.clientcommands.util;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Taken from <a href="https://gist.github.com/pseudogravity/294f12225c18bf319e4c1923dd664bd5">PseudoGravity's code</a>
 *
 * @author MC (PseudoGravity)
 */
public final class CombinedMedianEM {
    // a list of samples
    // each sample is actually a list of points
    // for each interval, convert it to a point by taking the median value
    // or... break the 50ms intervals into 2 25ms intervals for better accuracy
    public final List<DoubleList> data = new ArrayList<>();

    // width of the intervals
    private int width = SharedConstants.MILLIS_PER_TICK;

    private static final double MIN_PACKET_LOSS_RATE = 0.01;
    private static final double MAX_PACKET_LOSS_RATE = 0.5;
    private static final double MIN_SIGMA = 10;
    private static final double MAX_SIGMA = 1000;

    // three parameters and 2 constraints
    private double packetLossRate = 0.2;
    private double mu = 0.0;
    private double sigma = 500;

    public void update(int mspt, int maxTicksBefore, int maxTicksAfter) {
        width = mspt;

        // width of time window considered
        // all of the data points for all samples are within this window
        int beginTime = Mth.floor(mu + 0.5) - width / 2 - maxTicksBefore * width;
        int endTime = Mth.floor(mu + 0.5) + width / 2 + maxTicksAfter * width;

        double[] dropRate = new double[data.size()];
        Arrays.setAll(dropRate, i -> (double) data.get(i).size() * width / (endTime - beginTime));

        int bestTime = 0;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int time = beginTime; time <= endTime; time += 10) {
            // find score for each option for time
            double score = 0;
            for (int i = 0; i < data.size(); i++) {
                // for each sample, find the point which adds the least to the score
                DoubleList sample = data.get(i);
                double lambda = dropRate[i] / width;
                double bestSubScore = Double.POSITIVE_INFINITY;
                for (int j = 0; j < sample.size(); j++) {
                    double x = sample.getDouble(j);
                    double absDev = Math.abs(x - time);
                    absDev = (1 - Math.exp(-lambda * absDev)) / lambda; // further curbing outlier effects
                    if (absDev < bestSubScore) {
                        bestSubScore = absDev;
                    }
                }
                score += bestSubScore;
            }
            if (score < bestScore) {
                bestScore = score;
                bestTime = time;
            }
        }

        mu = bestTime;
        sigma = bestScore / data.size();
        sigma = Math.max(Math.min(sigma, MAX_SIGMA), MIN_SIGMA);

        for (int repeat = 0; repeat < 1; repeat++) {
            // E step
            // calculate weights (and classifications)
            var masses = new ArrayList<DoubleList>();
            for (int i = 0; i < data.size(); i++) {

                DoubleList sample = data.get(i);

                // process each sample
                double sum = 0;
                for (int j = 0; j < sample.size(); j++) {
                    double x = sample.getDouble(j);
                    sum += mass(x);
                }
                double pXandNorm = Math.min(sum, 1) * (1 - packetLossRate); // cap at 1

                double pXandUnif = dropRate[i] * packetLossRate;

                double pNorm = pXandNorm / (pXandNorm + pXandUnif);

                DoubleList mass = new DoubleArrayList();
                for (int j = 0; j < sample.size(); j++) {
                    double x = sample.getDouble(j);
                    mass.add(mass(x) / sum * pNorm);
                }

                masses.add(mass);
            }

            // M step
            // compute new best estimate for parameters
            double weightedsum = 0;
            double sumofweights = 0;
            for (int i = 0; i < data.size(); i++) {
                DoubleList sample = data.get(i);
                DoubleList mass = masses.get(i);
                for (int j = 0; j < sample.size(); j++) {
                    weightedsum += sample.getDouble(j) * mass.getDouble(j);
                    sumofweights += mass.getDouble(j);
                }
            }
            double muNext = weightedsum / sumofweights;

            double weightedsumofsquaredeviations = 0;
            for (int i = 0; i < data.size(); i++) {
                DoubleList sample = data.get(i);
                DoubleList mass = masses.get(i);
                for (int j = 0; j < sample.size(); j++) {
                    weightedsumofsquaredeviations += Math.pow(sample.getDouble(j) - muNext, 2) * mass.getDouble(j);
                }
            }
            double sigmaNext = Math.sqrt(weightedsumofsquaredeviations / sumofweights);
            sigmaNext = Math.max(Math.min(sigmaNext, MAX_SIGMA), MIN_SIGMA);

            double packetlossrateNext = (data.size() - sumofweights) / data.size();
            packetlossrateNext = Math.max(Math.min(packetlossrateNext, MAX_PACKET_LOSS_RATE), MIN_PACKET_LOSS_RATE);

            mu = muNext;
            sigma = sigmaNext;
            packetLossRate = packetlossrateNext;
        }
    }

    private double mass(double x) {
        // should be cdf(x+width/2)-cdf(x-width/2) but is simplified to pdf(x)*width and
        // capped at 1
        // to avoid pesky erf() functions
        double pdf = 1 / (sigma * Math.sqrt(2 * Math.PI)) * Math.exp(-Math.pow((x - mu) / sigma, 2) / 2);
        return Math.min(pdf * width, 1);
    }

    public double getResult() {
        return mu;
    }
}
