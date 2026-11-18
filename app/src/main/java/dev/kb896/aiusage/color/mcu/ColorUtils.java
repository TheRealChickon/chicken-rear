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
 * Colour arithmetic of Google's material-color-utilities (ColorUtils and the parts of MathUtils it needs): sRGB,
 * linear RGB, XYZ, L*a*b* and L*. See README.md in this folder for where this comes from and how it was checked.
 */
public final class ColorUtils {
  private ColorUtils() {}

  static final double[][] SRGB_TO_XYZ = {
    {0.41233895, 0.35762064, 0.18051042},
    {0.2126, 0.7152, 0.0722},
    {0.01932141, 0.11916382, 0.95034478},
  };

  static final double[][] XYZ_TO_SRGB = {
    {3.2413774792388685, -1.5376652402851851, -0.49885366846268053},
    {-0.9691452513005321, 1.8758853451067872, 0.04156585616912061},
    {0.05562093689691305, -0.20395524564742123, 1.0571799111220335},
  };

  static final double[] WHITE_POINT_D65 = {95.047, 100.0, 108.883};

  public static int argbFromRgb(int red, int green, int blue) {
    return (255 << 24) | ((red & 255) << 16) | ((green & 255) << 8) | (blue & 255);
  }

  public static int argbFromLinrgb(double[] linrgb) {
    int r = delinearized(linrgb[0]);
    int g = delinearized(linrgb[1]);
    int b = delinearized(linrgb[2]);
    return argbFromRgb(r, g, b);
  }

  public static int alphaFromArgb(int argb) { return (argb >> 24) & 255; }
  public static int redFromArgb(int argb) { return (argb >> 16) & 255; }
  public static int greenFromArgb(int argb) { return (argb >> 8) & 255; }
  public static int blueFromArgb(int argb) { return argb & 255; }

  public static int argbFromXyz(double x, double y, double z) {
    double[][] m = XYZ_TO_SRGB;
    double linearR = m[0][0] * x + m[0][1] * y + m[0][2] * z;
    double linearG = m[1][0] * x + m[1][1] * y + m[1][2] * z;
    double linearB = m[2][0] * x + m[2][1] * y + m[2][2] * z;
    return argbFromRgb(delinearized(linearR), delinearized(linearG), delinearized(linearB));
  }

  public static double[] xyzFromArgb(int argb) {
    double r = linearized(redFromArgb(argb));
    double g = linearized(greenFromArgb(argb));
    double b = linearized(blueFromArgb(argb));
    return matrixMultiply(new double[] {r, g, b}, SRGB_TO_XYZ);
  }

  public static int argbFromLab(double l, double a, double b) {
    double[] whitePoint = WHITE_POINT_D65;
    double fy = (l + 16.0) / 116.0;
    double fx = a / 500.0 + fy;
    double fz = fy - b / 200.0;
    double x = labInvf(fx) * whitePoint[0];
    double y = labInvf(fy) * whitePoint[1];
    double z = labInvf(fz) * whitePoint[2];
    return argbFromXyz(x, y, z);
  }

  public static double[] labFromArgb(int argb) {
    double linearR = linearized(redFromArgb(argb));
    double linearG = linearized(greenFromArgb(argb));
    double linearB = linearized(blueFromArgb(argb));
    double[][] m = SRGB_TO_XYZ;
    double x = m[0][0] * linearR + m[0][1] * linearG + m[0][2] * linearB;
    double y = m[1][0] * linearR + m[1][1] * linearG + m[1][2] * linearB;
    double z = m[2][0] * linearR + m[2][1] * linearG + m[2][2] * linearB;
    double[] whitePoint = WHITE_POINT_D65;
    double fx = labF(x / whitePoint[0]);
    double fy = labF(y / whitePoint[1]);
    double fz = labF(z / whitePoint[2]);
    return new double[] {116.0 * fy - 16, 500.0 * (fx - fy), 200.0 * (fy - fz)};
  }

  public static int argbFromLstar(double lstar) {
    double y = yFromLstar(lstar);
    int component = delinearized(y);
    return argbFromRgb(component, component, component);
  }

  public static double lstarFromArgb(int argb) {
    double y = xyzFromArgb(argb)[1];
    return 116.0 * labF(y / 100.0) - 16.0;
  }

  public static double yFromLstar(double lstar) {
    return 100.0 * labInvf((lstar + 16.0) / 116.0);
  }

  public static double lstarFromY(double y) {
    return labF(y / 100.0) * 116.0 - 16.0;
  }

  /** 0..255 to linear RGB, 0..100. */
  public static double linearized(int rgbComponent) {
    double normalized = rgbComponent / 255.0;
    if (normalized <= 0.040449936) {
      return normalized / 12.92 * 100.0;
    } else {
      return Math.pow((normalized + 0.055) / 1.055, 2.4) * 100.0;
    }
  }

  /** Linear RGB, 0..100, to 0..255. */
  public static int delinearized(double rgbComponent) {
    double normalized = rgbComponent / 100.0;
    double delinearized;
    if (normalized <= 0.0031308) {
      delinearized = normalized * 12.92;
    } else {
      delinearized = 1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055;
    }
    return clampInt(0, 255, (int) Math.round(delinearized * 255.0));
  }

  static double labF(double t) {
    double e = 216.0 / 24389.0;
    double kappa = 24389.0 / 27.0;
    if (t > e) {
      return Math.pow(t, 1.0 / 3.0);
    } else {
      return (kappa * t + 16) / 116;
    }
  }

  static double labInvf(double ft) {
    double e = 216.0 / 24389.0;
    double kappa = 24389.0 / 27.0;
    double ft3 = ft * ft * ft;
    if (ft3 > e) {
      return ft3;
    } else {
      return (116 * ft - 16) / kappa;
    }
  }

  // ---- MathUtils ----

  static int clampInt(int min, int max, int input) {
    if (input < min) return min;
    else if (input > max) return max;
    return input;
  }

  static double lerp(double start, double stop, double amount) {
    return (1.0 - amount) * start + amount * stop;
  }

  static int sanitizeDegreesInt(int degrees) {
    degrees = degrees % 360;
    if (degrees < 0) degrees = degrees + 360;
    return degrees;
  }

  public static double sanitizeDegreesDouble(double degrees) {
    degrees = degrees % 360.0;
    if (degrees < 0) degrees = degrees + 360.0;
    return degrees;
  }

  static double signum(double num) {
    if (num < 0) return -1.0;
    else if (num == 0) return 0.0;
    else return 1.0;
  }

  static double[] matrixMultiply(double[] row, double[][] matrix) {
    double a = row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2];
    double b = row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2];
    double c = row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2];
    return new double[] {a, b, c};
  }
}
