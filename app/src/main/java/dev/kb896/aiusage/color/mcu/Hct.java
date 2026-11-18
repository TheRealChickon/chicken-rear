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

/**
 * HCT: hue and chroma from CAM16, tone = L*. The colour space Xiaomi's libMiMainColor takes its tones in.
 *
 * NOT THE LIBRARY'S SOLVER. The library's HctSolver finds the colour of a given hue, chroma and tone by a Newton
 * search on J with tables for the gamut's edge. Here the same answer is found by plain bisection: J for the tone at
 * the asked chroma, and where that lies outside the screen's colours, the largest chroma at that hue and tone that
 * does not (what the library's solver gives as well). Checked against com.google.android.material 1.14.0's own
 * classes: README.md.
 */
public final class Hct {
  private final double hue;
  private final double chroma;
  private final double tone;
  private final int argb;

  private Hct(int argb) {
    Cam16 cam = Cam16.fromInt(argb);
    this.hue = cam.hue;
    this.chroma = cam.chroma;
    this.tone = ColorUtils.lstarFromArgb(argb);
    this.argb = argb;
  }

  public static Hct fromInt(int argb) { return new Hct(argb); }

  public static Hct from(double hue, double chroma, double tone) { return new Hct(solveToInt(hue, chroma, tone)); }

  public double getHue() { return hue; }
  public double getChroma() { return chroma; }
  public double getTone() { return tone; }
  public int toInt() { return argb; }

  /** The colour with [hueDegrees], as much of [chroma] as the screen can show at [lstar], and the tone [lstar]. */
  public static int solveToInt(double hueDegrees, double chroma, double lstar) {
    if (chroma < 0.0001 || lstar < 0.0001 || lstar > 99.9999) return ColorUtils.argbFromLstar(lstar);
    double hueRadians = Math.toRadians(ColorUtils.sanitizeDegreesDouble(hueDegrees));
    double y = ColorUtils.yFromLstar(lstar);
    double[] rgb = new double[3];
    if (inGamutAt(hueRadians, chroma, y, rgb)) return ColorUtils.argbFromLinrgb(rgb);
    // The asked chroma is beyond the screen there: the most it can show, at this hue and tone.
    double lo = 0.0, hi = chroma;
    double[] best = null;
    for (int i = 0; i < 40; i++) {
      double mid = (lo + hi) / 2.0;
      if (inGamutAt(hueRadians, mid, y, rgb)) { best = rgb.clone(); lo = mid; } else hi = mid;
    }
    return best != null ? ColorUtils.argbFromLinrgb(best) : ColorUtils.argbFromLstar(lstar);
  }

  /** Puts in [out] the linear RGB of [chroma] at [hueRadians] whose Y is [y]; whether it is inside the screen's colours. */
  private static boolean inGamutAt(double hueRadians, double chroma, double y, double[] out) {
    // Y rises with J for a given hue and chroma: bisection on J.
    double lo = 0.0, hi = 200.0;
    for (int i = 0; i < 64; i++) {
      double mid = (lo + hi) / 2.0;
      Cam16.linrgbFromJch(mid, chroma, hueRadians, out);
      double ym = 0.2126 * out[0] + 0.7152 * out[1] + 0.0722 * out[2];
      if (ym < y) lo = mid; else hi = mid;
    }
    Cam16.linrgbFromJch((lo + hi) / 2.0, chroma, hueRadians, out);
    // Far out of the screen's colours the inverse need not rise with J everywhere: a J that missed the tone is no answer.
    double ym = 0.2126 * out[0] + 0.7152 * out[1] + 0.0722 * out[2];
    if (Math.abs(ym - y) > 0.002 + 0.001 * y) return false;
    double e = 1e-6;
    return out[0] >= -e && out[1] >= -e && out[2] >= -e && out[0] <= 100.0 + e && out[1] <= 100.0 + e && out[2] <= 100.0 + e;
  }
}
