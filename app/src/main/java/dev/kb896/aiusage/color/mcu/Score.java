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
import java.util.List;
import java.util.Map;

/**
 * material-color-utilities' Score: which of a picture's colours suits a theme best. A colour's hue counts by how
 * much of the picture lies within 15 degrees of it, and its chroma by how near it is to 48 (more above than
 * below); colours under chroma 5, or whose hue is under 1 % of the picture, are left out.
 *
 * Only the first (the best) is needed here, so only that is given; the library's choice of further colours at
 * least 15 to 90 degrees apart is not ported.
 */
public final class Score {
  private Score() {}

  private static final double TARGET_CHROMA = 48.0;
  private static final double WEIGHT_PROPORTION = 0.7;
  private static final double WEIGHT_CHROMA_ABOVE = 0.3;
  private static final double WEIGHT_CHROMA_BELOW = 0.1;
  private static final double CUTOFF_CHROMA = 5.0;
  private static final double CUTOFF_EXCITED_PROPORTION = 0.01;

  /** The best-scored colour of [colorsToPopulation], or 0 when none passes (a grey picture): the library's first. */
  public static int best(Map<Integer, Integer> colorsToPopulation) {
    List<Hct> colorsHct = new ArrayList<>();
    int[] huePopulation = new int[360];
    double populationSum = 0.;
    for (Map.Entry<Integer, Integer> entry : colorsToPopulation.entrySet()) {
      Hct hct = Hct.fromInt(entry.getKey());
      colorsHct.add(hct);
      int hue = (int) Math.floor(hct.getHue());
      huePopulation[hue] += entry.getValue();
      populationSum += entry.getValue();
    }
    if (populationSum <= 0) return 0;

    double[] hueExcitedProportions = new double[360];
    for (int hue = 0; hue < 360; hue++) {
      double proportion = huePopulation[hue] / populationSum;
      for (int i = hue - 14; i < hue + 16; i++) {
        int neighborHue = ColorUtils.sanitizeDegreesInt(i);
        hueExcitedProportions[neighborHue] += proportion;
      }
    }

    // The library sorts by score, stably, and takes the first: the first of the highest, in the map's order.
    Hct best = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (Hct hct : colorsHct) {
      int hue = ColorUtils.sanitizeDegreesInt((int) Math.round(hct.getHue()));
      double proportion = hueExcitedProportions[hue];
      if (hct.getChroma() < CUTOFF_CHROMA || proportion <= CUTOFF_EXCITED_PROPORTION) continue;
      double proportionScore = proportion * 100.0 * WEIGHT_PROPORTION;
      double chromaWeight = hct.getChroma() < TARGET_CHROMA ? WEIGHT_CHROMA_BELOW : WEIGHT_CHROMA_ABOVE;
      double chromaScore = (hct.getChroma() - TARGET_CHROMA) * chromaWeight;
      double score = proportionScore + chromaScore;
      if (score > bestScore) {
        bestScore = score;
        best = hct;
      }
    }
    return best == null ? 0 : best.toInt();
  }
}
