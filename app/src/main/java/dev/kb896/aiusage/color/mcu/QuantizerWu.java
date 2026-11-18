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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wu's colour quantizer, as material-color-utilities has it: a histogram of 32 levels a channel, cut into boxes
 * of least variance. Its integer arithmetic (including where it overflows on large pictures) is kept as the
 * library's, so the boxes come out the same.
 */
final class QuantizerWu {
  int[] weights;
  int[] momentsR;
  int[] momentsG;
  int[] momentsB;
  double[] moments;
  Box[] cubes;

  private static final int INDEX_BITS = 5;
  private static final int INDEX_COUNT = 33;
  private static final int TOTAL_SIZE = 35937;

  /** The opaque pixels' colours and how many of each, in the order first seen (the library's QuantizerMap). */
  static Map<Integer, Integer> countOpaque(int[] pixels) {
    Map<Integer, Integer> colorToCount = new LinkedHashMap<>();
    for (int pixel : pixels) {
      int alpha = ColorUtils.alphaFromArgb(pixel);
      if (alpha < 255) continue;
      Integer had = colorToCount.get(pixel);
      colorToCount.put(pixel, had == null ? 1 : had + 1);
    }
    return colorToCount;
  }

  /** At most [colorCount] colours (as keys; the values are 0). */
  Map<Integer, Integer> quantize(int[] pixels, int colorCount) {
    constructHistogram(countOpaque(pixels));
    createMoments();
    int resultCount = createBoxes(colorCount);
    List<Integer> colors = createResult(resultCount);
    Map<Integer, Integer> resultMap = new LinkedHashMap<>();
    for (int color : colors) resultMap.put(color, 0);
    return resultMap;
  }

  static int getIndex(int r, int g, int b) {
    return (r << (INDEX_BITS * 2)) + (r << (INDEX_BITS + 1)) + r + (g << INDEX_BITS) + g + b;
  }

  void constructHistogram(Map<Integer, Integer> pixels) {
    weights = new int[TOTAL_SIZE];
    momentsR = new int[TOTAL_SIZE];
    momentsG = new int[TOTAL_SIZE];
    momentsB = new int[TOTAL_SIZE];
    moments = new double[TOTAL_SIZE];
    for (Map.Entry<Integer, Integer> pair : pixels.entrySet()) {
      int pixel = pair.getKey();
      int count = pair.getValue();
      int red = ColorUtils.redFromArgb(pixel);
      int green = ColorUtils.greenFromArgb(pixel);
      int blue = ColorUtils.blueFromArgb(pixel);
      int bitsToRemove = 8 - INDEX_BITS;
      int iR = (red >> bitsToRemove) + 1;
      int iG = (green >> bitsToRemove) + 1;
      int iB = (blue >> bitsToRemove) + 1;
      int index = getIndex(iR, iG, iB);
      weights[index] += count;
      momentsR[index] += (red * count);
      momentsG[index] += (green * count);
      momentsB[index] += (blue * count);
      moments[index] += (count * ((red * red) + (green * green) + (blue * blue)));
    }
  }

  void createMoments() {
    for (int r = 1; r < INDEX_COUNT; ++r) {
      int[] area = new int[INDEX_COUNT];
      int[] areaR = new int[INDEX_COUNT];
      int[] areaG = new int[INDEX_COUNT];
      int[] areaB = new int[INDEX_COUNT];
      double[] area2 = new double[INDEX_COUNT];
      for (int g = 1; g < INDEX_COUNT; ++g) {
        int line = 0;
        int lineR = 0;
        int lineG = 0;
        int lineB = 0;
        double line2 = 0.0;
        for (int b = 1; b < INDEX_COUNT; ++b) {
          int index = getIndex(r, g, b);
          line += weights[index];
          lineR += momentsR[index];
          lineG += momentsG[index];
          lineB += momentsB[index];
          line2 += moments[index];
          area[b] += line;
          areaR[b] += lineR;
          areaG[b] += lineG;
          areaB[b] += lineB;
          area2[b] += line2;
          int previousIndex = getIndex(r - 1, g, b);
          weights[index] = weights[previousIndex] + area[b];
          momentsR[index] = momentsR[previousIndex] + areaR[b];
          momentsG[index] = momentsG[previousIndex] + areaG[b];
          momentsB[index] = momentsB[previousIndex] + areaB[b];
          moments[index] = moments[previousIndex] + area2[b];
        }
      }
    }
  }

  /** How many boxes were made. */
  int createBoxes(int maxColorCount) {
    cubes = new Box[maxColorCount];
    for (int i = 0; i < maxColorCount; i++) cubes[i] = new Box();
    double[] volumeVariance = new double[maxColorCount];
    Box firstBox = cubes[0];
    firstBox.r1 = INDEX_COUNT - 1;
    firstBox.g1 = INDEX_COUNT - 1;
    firstBox.b1 = INDEX_COUNT - 1;
    int generatedColorCount = maxColorCount;
    int next = 0;
    for (int i = 1; i < maxColorCount; i++) {
      if (cut(cubes[next], cubes[i])) {
        volumeVariance[next] = (cubes[next].vol > 1) ? variance(cubes[next]) : 0.0;
        volumeVariance[i] = (cubes[i].vol > 1) ? variance(cubes[i]) : 0.0;
      } else {
        volumeVariance[next] = 0.0;
        i--;
      }
      next = 0;
      double temp = volumeVariance[0];
      for (int j = 1; j <= i; j++) {
        if (volumeVariance[j] > temp) {
          temp = volumeVariance[j];
          next = j;
        }
      }
      if (temp <= 0.0) {
        generatedColorCount = i + 1;
        break;
      }
    }
    return generatedColorCount;
  }

  List<Integer> createResult(int colorCount) {
    List<Integer> colors = new ArrayList<>();
    for (int i = 0; i < colorCount; ++i) {
      Box cube = cubes[i];
      int weight = volume(cube, weights);
      if (weight > 0) {
        int r = volume(cube, momentsR) / weight;
        int g = volume(cube, momentsG) / weight;
        int b = volume(cube, momentsB) / weight;
        int color = (255 << 24) | ((r & 0x0ff) << 16) | ((g & 0x0ff) << 8) | (b & 0x0ff);
        colors.add(color);
      }
    }
    return colors;
  }

  double variance(Box cube) {
    int dr = volume(cube, momentsR);
    int dg = volume(cube, momentsG);
    int db = volume(cube, momentsB);
    double xx =
        moments[getIndex(cube.r1, cube.g1, cube.b1)]
            - moments[getIndex(cube.r1, cube.g1, cube.b0)]
            - moments[getIndex(cube.r1, cube.g0, cube.b1)]
            + moments[getIndex(cube.r1, cube.g0, cube.b0)]
            - moments[getIndex(cube.r0, cube.g1, cube.b1)]
            + moments[getIndex(cube.r0, cube.g1, cube.b0)]
            + moments[getIndex(cube.r0, cube.g0, cube.b1)]
            - moments[getIndex(cube.r0, cube.g0, cube.b0)];
    int hypotenuse = dr * dr + dg * dg + db * db;
    int volume = volume(cube, weights);
    return xx - hypotenuse / ((double) volume);
  }

  boolean cut(Box one, Box two) {
    int wholeR = volume(one, momentsR);
    int wholeG = volume(one, momentsG);
    int wholeB = volume(one, momentsB);
    int wholeW = volume(one, weights);

    double[] maxR = maximize(one, RED, one.r0 + 1, one.r1, wholeR, wholeG, wholeB, wholeW);
    double[] maxG = maximize(one, GREEN, one.g0 + 1, one.g1, wholeR, wholeG, wholeB, wholeW);
    double[] maxB = maximize(one, BLUE, one.b0 + 1, one.b1, wholeR, wholeG, wholeB, wholeW);
    int cutDirection;
    double r = maxR[1];
    double g = maxG[1];
    double b = maxB[1];
    if (r >= g && r >= b) {
      if (maxR[0] < 0) return false;
      cutDirection = RED;
    } else if (g >= r && g >= b) {
      cutDirection = GREEN;
    } else {
      cutDirection = BLUE;
    }

    two.r1 = one.r1;
    two.g1 = one.g1;
    two.b1 = one.b1;

    switch (cutDirection) {
      case RED:
        one.r1 = (int) maxR[0];
        two.r0 = one.r1;
        two.g0 = one.g0;
        two.b0 = one.b0;
        break;
      case GREEN:
        one.g1 = (int) maxG[0];
        two.r0 = one.r0;
        two.g0 = one.g1;
        two.b0 = one.b0;
        break;
      default:
        one.b1 = (int) maxB[0];
        two.r0 = one.r0;
        two.g0 = one.g0;
        two.b0 = one.b1;
        break;
    }

    one.vol = (one.r1 - one.r0) * (one.g1 - one.g0) * (one.b1 - one.b0);
    two.vol = (two.r1 - two.r0) * (two.g1 - two.g0) * (two.b1 - two.b0);
    return true;
  }

  /** {cut location (-1 for none), maximum}. */
  double[] maximize(Box cube, int direction, int first, int last, int wholeR, int wholeG, int wholeB, int wholeW) {
    int bottomR = bottom(cube, direction, momentsR);
    int bottomG = bottom(cube, direction, momentsG);
    int bottomB = bottom(cube, direction, momentsB);
    int bottomW = bottom(cube, direction, weights);

    double max = 0.0;
    int cut = -1;

    int halfR;
    int halfG;
    int halfB;
    int halfW;
    for (int i = first; i < last; i++) {
      halfR = bottomR + top(cube, direction, i, momentsR);
      halfG = bottomG + top(cube, direction, i, momentsG);
      halfB = bottomB + top(cube, direction, i, momentsB);
      halfW = bottomW + top(cube, direction, i, weights);
      if (halfW == 0) continue;

      double tempNumerator = halfR * halfR + halfG * halfG + halfB * halfB;
      double tempDenominator = halfW;
      double temp = tempNumerator / tempDenominator;

      halfR = wholeR - halfR;
      halfG = wholeG - halfG;
      halfB = wholeB - halfB;
      halfW = wholeW - halfW;
      if (halfW == 0) continue;

      tempNumerator = halfR * halfR + halfG * halfG + halfB * halfB;
      tempDenominator = halfW;
      temp += (tempNumerator / tempDenominator);

      if (temp > max) {
        max = temp;
        cut = i;
      }
    }
    return new double[] {cut, max};
  }

  static int volume(Box cube, int[] moment) {
    return (moment[getIndex(cube.r1, cube.g1, cube.b1)]
        - moment[getIndex(cube.r1, cube.g1, cube.b0)]
        - moment[getIndex(cube.r1, cube.g0, cube.b1)]
        + moment[getIndex(cube.r1, cube.g0, cube.b0)]
        - moment[getIndex(cube.r0, cube.g1, cube.b1)]
        + moment[getIndex(cube.r0, cube.g1, cube.b0)]
        + moment[getIndex(cube.r0, cube.g0, cube.b1)]
        - moment[getIndex(cube.r0, cube.g0, cube.b0)]);
  }

  static int bottom(Box cube, int direction, int[] moment) {
    switch (direction) {
      case RED:
        return -moment[getIndex(cube.r0, cube.g1, cube.b1)]
            + moment[getIndex(cube.r0, cube.g1, cube.b0)]
            + moment[getIndex(cube.r0, cube.g0, cube.b1)]
            - moment[getIndex(cube.r0, cube.g0, cube.b0)];
      case GREEN:
        return -moment[getIndex(cube.r1, cube.g0, cube.b1)]
            + moment[getIndex(cube.r1, cube.g0, cube.b0)]
            + moment[getIndex(cube.r0, cube.g0, cube.b1)]
            - moment[getIndex(cube.r0, cube.g0, cube.b0)];
      default:
        return -moment[getIndex(cube.r1, cube.g1, cube.b0)]
            + moment[getIndex(cube.r1, cube.g0, cube.b0)]
            + moment[getIndex(cube.r0, cube.g1, cube.b0)]
            - moment[getIndex(cube.r0, cube.g0, cube.b0)];
    }
  }

  static int top(Box cube, int direction, int position, int[] moment) {
    switch (direction) {
      case RED:
        return (moment[getIndex(position, cube.g1, cube.b1)]
            - moment[getIndex(position, cube.g1, cube.b0)]
            - moment[getIndex(position, cube.g0, cube.b1)]
            + moment[getIndex(position, cube.g0, cube.b0)]);
      case GREEN:
        return (moment[getIndex(cube.r1, position, cube.b1)]
            - moment[getIndex(cube.r1, position, cube.b0)]
            - moment[getIndex(cube.r0, position, cube.b1)]
            + moment[getIndex(cube.r0, position, cube.b0)]);
      default:
        return (moment[getIndex(cube.r1, cube.g1, position)]
            - moment[getIndex(cube.r1, cube.g0, position)]
            - moment[getIndex(cube.r0, cube.g1, position)]
            + moment[getIndex(cube.r0, cube.g0, position)]);
    }
  }

  private static final int RED = 0;
  private static final int GREEN = 1;
  private static final int BLUE = 2;

  static final class Box {
    int r0 = 0;
    int r1 = 0;
    int g0 = 0;
    int g1 = 0;
    int b0 = 0;
    int b1 = 0;
    int vol = 0;
  }
}
