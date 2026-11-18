/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.kb896.aiusage.color.mcu;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Celebi's quantizer, as material-color-utilities has it: Wu's boxes as the start, then weighted k-means in L*a*b*
 * (QuantizerWsmeans, with its fixed seed, so the same picture always gives the same colours).
 */
public final class QuantizerCelebi {
  private QuantizerCelebi() {}

  /** The picture's colours, at most [maxColors], each with how many pixels it stands for. */
  public static Map<Integer, Integer> quantize(int[] pixels, int maxColors) {
    QuantizerWu wu = new QuantizerWu();
    Map<Integer, Integer> wuResult = wu.quantize(pixels, maxColors);
    int[] wuClusters = new int[wuResult.size()];
    int index = 0;
    for (int argb : wuResult.keySet()) wuClusters[index++] = argb;
    return wsmeans(pixels, wuClusters, maxColors);
  }

  private static final int MAX_ITERATIONS = 10;
  private static final double MIN_MOVEMENT_DISTANCE = 3.0;

  private static final class Distance implements Comparable<Distance> {
    int index = -1;
    double distance = -1;

    @Override
    public int compareTo(Distance other) {
      return ((Double) this.distance).compareTo(other.distance);
    }
  }

  private static double distance(double[] one, double[] two) {
    double dL = (one[0] - two[0]);
    double dA = (one[1] - two[1]);
    double dB = (one[2] - two[2]);
    return (dL * dL + dA * dA + dB * dB);
  }

  static Map<Integer, Integer> wsmeans(int[] inputPixels, int[] startingClusters, int maxColors) {
    Random random = new Random(0x42688);
    Map<Integer, Integer> pixelToCount = new LinkedHashMap<>();
    double[][] points = new double[inputPixels.length][];
    int[] pixels = new int[inputPixels.length];
    int pointCount = 0;
    for (int inputPixel : inputPixels) {
      Integer pixelCount = pixelToCount.get(inputPixel);
      if (pixelCount == null) {
        points[pointCount] = ColorUtils.labFromArgb(inputPixel);
        pixels[pointCount] = inputPixel;
        pointCount++;
        pixelToCount.put(inputPixel, 1);
      } else {
        pixelToCount.put(inputPixel, pixelCount + 1);
      }
    }

    int[] counts = new int[pointCount];
    for (int i = 0; i < pointCount; i++) counts[i] = pixelToCount.get(pixels[i]);

    int clusterCount = Math.min(maxColors, pointCount);
    if (startingClusters.length != 0) clusterCount = Math.min(clusterCount, startingClusters.length);
    // (The library goes on with no clusters and fails; a picture with nothing opaque in it has no colours.)
    if (clusterCount == 0 || startingClusters.length == 0) return new LinkedHashMap<>();

    double[][] clusters = new double[clusterCount][];
    for (int i = 0; i < startingClusters.length && i < clusterCount; i++) clusters[i] = ColorUtils.labFromArgb(startingClusters[i]);

    int[] clusterIndices = new int[pointCount];
    for (int i = 0; i < pointCount; i++) clusterIndices[i] = random.nextInt(clusterCount);

    Distance[][] distanceToIndexMatrix = new Distance[clusterCount][];
    for (int i = 0; i < clusterCount; i++) {
      distanceToIndexMatrix[i] = new Distance[clusterCount];
      for (int j = 0; j < clusterCount; j++) distanceToIndexMatrix[i][j] = new Distance();
    }

    int[] pixelCountSums = new int[clusterCount];
    for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
      for (int i = 0; i < clusterCount; i++) {
        for (int j = i + 1; j < clusterCount; j++) {
          double distance = distance(clusters[i], clusters[j]);
          distanceToIndexMatrix[j][i].distance = distance;
          distanceToIndexMatrix[j][i].index = i;
          distanceToIndexMatrix[i][j].distance = distance;
          distanceToIndexMatrix[i][j].index = j;
        }
        Arrays.sort(distanceToIndexMatrix[i]);
      }

      int pointsMoved = 0;
      for (int i = 0; i < pointCount; i++) {
        double[] point = points[i];
        int previousClusterIndex = clusterIndices[i];
        double[] previousCluster = clusters[previousClusterIndex];
        double previousDistance = distance(point, previousCluster);

        double minimumDistance = previousDistance;
        int newClusterIndex = -1;
        for (int j = 0; j < clusterCount; j++) {
          if (distanceToIndexMatrix[previousClusterIndex][j].distance >= 4 * previousDistance) continue;
          double distance = distance(point, clusters[j]);
          if (distance < minimumDistance) {
            minimumDistance = distance;
            newClusterIndex = j;
          }
        }
        if (newClusterIndex != -1) {
          double distanceChange = Math.abs(Math.sqrt(minimumDistance) - Math.sqrt(previousDistance));
          if (distanceChange > MIN_MOVEMENT_DISTANCE) {
            pointsMoved++;
            clusterIndices[i] = newClusterIndex;
          }
        }
      }

      if (pointsMoved == 0 && iteration != 0) break;

      double[] componentASums = new double[clusterCount];
      double[] componentBSums = new double[clusterCount];
      double[] componentCSums = new double[clusterCount];
      Arrays.fill(pixelCountSums, 0);
      for (int i = 0; i < pointCount; i++) {
        int clusterIndex = clusterIndices[i];
        double[] point = points[i];
        int count = counts[i];
        pixelCountSums[clusterIndex] += count;
        componentASums[clusterIndex] += (point[0] * count);
        componentBSums[clusterIndex] += (point[1] * count);
        componentCSums[clusterIndex] += (point[2] * count);
      }

      for (int i = 0; i < clusterCount; i++) {
        int count = pixelCountSums[i];
        if (count == 0) {
          clusters[i] = new double[] {0.0, 0.0, 0.0};
          continue;
        }
        double a = componentASums[i] / count;
        double b = componentBSums[i] / count;
        double c = componentCSums[i] / count;
        clusters[i][0] = a;
        clusters[i][1] = b;
        clusters[i][2] = c;
      }
    }

    Map<Integer, Integer> argbToPopulation = new LinkedHashMap<>();
    for (int i = 0; i < clusterCount; i++) {
      int count = pixelCountSums[i];
      if (count == 0) continue;
      int possibleNewCluster = ColorUtils.argbFromLab(clusters[i][0], clusters[i][1], clusters[i][2]);
      if (argbToPopulation.containsKey(possibleNewCluster)) continue;
      argbToPopulation.put(possibleNewCluster, count);
    }
    return argbToPopulation;
  }
}
