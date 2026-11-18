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
 * CAM16 under material-color-utilities' default viewing conditions (ViewingConditions.DEFAULT: D65, a mid-grey
 * background, average surround), forward and back. Hue and chroma of HCT are CAM16's.
 */
public final class Cam16 {
  static final double[][] XYZ_TO_CAM16RGB = {
    {0.401288, 0.650173, -0.051461},
    {-0.250268, 1.204414, 0.045854},
    {-0.002079, 0.048952, 0.953127},
  };

  static final double[][] CAM16RGB_TO_XYZ = {
    {1.8620678, -1.0112547, 0.14918678},
    {0.38752654, 0.62144744, -0.00897398},
    {-0.01584150, -0.03412294, 1.0499644},
  };

  /** The viewing conditions (ViewingConditions.DEFAULT of the library). */
  static final class Vc {
    final double n, aw, nbb, ncb, c, nc, fl, flRoot, z;
    final double[] rgbD;

    Vc(double[] whitePoint, double adaptingLuminance, double backgroundLstar, double surround, boolean discountingIlluminant) {
      backgroundLstar = Math.max(0.1, backgroundLstar);
      double[][] matrix = XYZ_TO_CAM16RGB;
      double[] xyz = whitePoint;
      double rW = (xyz[0] * matrix[0][0]) + (xyz[1] * matrix[0][1]) + (xyz[2] * matrix[0][2]);
      double gW = (xyz[0] * matrix[1][0]) + (xyz[1] * matrix[1][1]) + (xyz[2] * matrix[1][2]);
      double bW = (xyz[0] * matrix[2][0]) + (xyz[1] * matrix[2][1]) + (xyz[2] * matrix[2][2]);
      double f = 0.8 + (surround / 10.0);
      double c = (f >= 0.9) ? ColorUtils.lerp(0.59, 0.69, ((f - 0.9) * 10.0)) : ColorUtils.lerp(0.525, 0.59, ((f - 0.8) * 10.0));
      double d = discountingIlluminant ? 1.0 : f * (1.0 - ((1.0 / 3.6) * Math.exp((-adaptingLuminance - 42.0) / 92.0)));
      d = Math.max(0.0, Math.min(1.0, d));
      double nc = f;
      double[] rgbD = {d * (100.0 / rW) + 1.0 - d, d * (100.0 / gW) + 1.0 - d, d * (100.0 / bW) + 1.0 - d};
      double k = 1.0 / (5.0 * adaptingLuminance + 1.0);
      double k4 = k * k * k * k;
      double k4F = 1.0 - k4;
      double fl = (k4 * adaptingLuminance) + (0.1 * k4F * k4F * Math.cbrt(5.0 * adaptingLuminance));
      double n = (ColorUtils.yFromLstar(backgroundLstar) / whitePoint[1]);
      double z = 1.48 + Math.sqrt(n);
      double nbb = 0.725 / Math.pow(n, 0.2);
      double ncb = nbb;
      double[] rgbAFactors = {
        Math.pow(fl * rgbD[0] * rW / 100.0, 0.42),
        Math.pow(fl * rgbD[1] * gW / 100.0, 0.42),
        Math.pow(fl * rgbD[2] * bW / 100.0, 0.42),
      };
      double[] rgbA = {
        (400.0 * rgbAFactors[0]) / (rgbAFactors[0] + 27.13),
        (400.0 * rgbAFactors[1]) / (rgbAFactors[1] + 27.13),
        (400.0 * rgbAFactors[2]) / (rgbAFactors[2] + 27.13),
      };
      double aw = ((2.0 * rgbA[0]) + rgbA[1] + (0.05 * rgbA[2])) * nbb;
      this.n = n; this.aw = aw; this.nbb = nbb; this.ncb = ncb; this.c = c; this.nc = nc;
      this.rgbD = rgbD; this.fl = fl; this.flRoot = Math.pow(fl, 0.25); this.z = z;
    }
  }

  static final Vc DEFAULT = new Vc(ColorUtils.WHITE_POINT_D65, (200.0 / Math.PI * ColorUtils.yFromLstar(50.0) / 100.0), 50.0, 2.0, false);

  public final double hue;
  public final double chroma;
  public final double j;

  private Cam16(double hue, double chroma, double j) {
    this.hue = hue; this.chroma = chroma; this.j = j;
  }

  public static Cam16 fromInt(int argb) {
    int red = (argb & 0x00ff0000) >> 16;
    int green = (argb & 0x0000ff00) >> 8;
    int blue = (argb & 0x000000ff);
    double redL = ColorUtils.linearized(red);
    double greenL = ColorUtils.linearized(green);
    double blueL = ColorUtils.linearized(blue);
    double x = 0.41233895 * redL + 0.35762064 * greenL + 0.18051042 * blueL;
    double y = 0.2126 * redL + 0.7152 * greenL + 0.0722 * blueL;
    double z = 0.01932141 * redL + 0.11916382 * greenL + 0.95034478 * blueL;
    return fromXyz(x, y, z, DEFAULT);
  }

  static Cam16 fromXyz(double x, double y, double z, Vc vc) {
    double[][] matrix = XYZ_TO_CAM16RGB;
    double rT = (x * matrix[0][0]) + (y * matrix[0][1]) + (z * matrix[0][2]);
    double gT = (x * matrix[1][0]) + (y * matrix[1][1]) + (z * matrix[1][2]);
    double bT = (x * matrix[2][0]) + (y * matrix[2][1]) + (z * matrix[2][2]);
    double rD = vc.rgbD[0] * rT;
    double gD = vc.rgbD[1] * gT;
    double bD = vc.rgbD[2] * bT;
    double rAF = Math.pow(vc.fl * Math.abs(rD) / 100.0, 0.42);
    double gAF = Math.pow(vc.fl * Math.abs(gD) / 100.0, 0.42);
    double bAF = Math.pow(vc.fl * Math.abs(bD) / 100.0, 0.42);
    double rA = Math.signum(rD) * 400.0 * rAF / (rAF + 27.13);
    double gA = Math.signum(gD) * 400.0 * gAF / (gAF + 27.13);
    double bA = Math.signum(bD) * 400.0 * bAF / (bAF + 27.13);
    double a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
    double b = (rA + gA - 2.0 * bA) / 9.0;
    double u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0;
    double p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0;
    double atan2 = Math.atan2(b, a);
    double atanDegrees = Math.toDegrees(atan2);
    double hue = atanDegrees < 0 ? atanDegrees + 360.0 : atanDegrees >= 360 ? atanDegrees - 360.0 : atanDegrees;
    double ac = p2 * vc.nbb;
    double j = 100.0 * Math.pow(ac / vc.aw, vc.c * vc.z);
    double huePrime = (hue < 20.14) ? hue + 360 : hue;
    double eHue = 0.25 * (Math.cos(Math.toRadians(huePrime) + 2.0) + 3.8);
    double p1 = 50000.0 / 13.0 * eHue * vc.nc * vc.ncb;
    double t = p1 * Math.hypot(a, b) / (u + 0.305);
    double alpha = Math.pow(1.64 - Math.pow(0.29, vc.n), 0.73) * Math.pow(t, 0.9);
    double c = alpha * Math.sqrt(j / 100.0);
    return new Cam16(hue, c, j);
  }

  /**
   * The linear RGB (0..100, NOT clamped: out of the screen's gamut shows as a component under 0 or over 100) of the
   * colour with CAM16 lightness [j], chroma [chroma] and hue [hueRadians].
   */
  static void linrgbFromJch(double j, double chroma, double hueRadians, double[] out) {
    Vc vc = DEFAULT;
    double alpha = (chroma == 0.0 || j == 0.0) ? 0.0 : chroma / Math.sqrt(j / 100.0);
    double t = Math.pow(alpha / Math.pow(1.64 - Math.pow(0.29, vc.n), 0.73), 1.0 / 0.9);
    double eHue = 0.25 * (Math.cos(hueRadians + 2.0) + 3.8);
    double ac = vc.aw * Math.pow(j / 100.0, 1.0 / vc.c / vc.z);
    double p1 = eHue * (50000.0 / 13.0) * vc.nc * vc.ncb;
    double p2 = (ac / vc.nbb);
    double hSin = Math.sin(hueRadians);
    double hCos = Math.cos(hueRadians);
    double gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin);
    double a = gamma * hCos;
    double b = gamma * hSin;
    double rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
    double gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
    double bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;
    double rCBase = Math.max(0, (27.13 * Math.abs(rA)) / (400.0 - Math.abs(rA)));
    double rC = Math.signum(rA) * (100.0 / vc.fl) * Math.pow(rCBase, 1.0 / 0.42);
    double gCBase = Math.max(0, (27.13 * Math.abs(gA)) / (400.0 - Math.abs(gA)));
    double gC = Math.signum(gA) * (100.0 / vc.fl) * Math.pow(gCBase, 1.0 / 0.42);
    double bCBase = Math.max(0, (27.13 * Math.abs(bA)) / (400.0 - Math.abs(bA)));
    double bC = Math.signum(bA) * (100.0 / vc.fl) * Math.pow(bCBase, 1.0 / 0.42);
    double rF = rC / vc.rgbD[0];
    double gF = gC / vc.rgbD[1];
    double bF = bC / vc.rgbD[2];
    double[][] m = CAM16RGB_TO_XYZ;
    double x = (rF * m[0][0]) + (gF * m[0][1]) + (bF * m[0][2]);
    double y = (rF * m[1][0]) + (gF * m[1][1]) + (bF * m[1][2]);
    double z = (rF * m[2][0]) + (gF * m[2][1]) + (bF * m[2][2]);
    double[][] s = ColorUtils.XYZ_TO_SRGB;
    out[0] = s[0][0] * x + s[0][1] * y + s[0][2] * z;
    out[1] = s[1][0] * x + s[1][1] * y + s[1][2] * z;
    out[2] = s[2][0] * x + s[2][1] * y + s[2][2] * z;
  }
}
