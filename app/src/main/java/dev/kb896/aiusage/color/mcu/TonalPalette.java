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

/** One hue at one chroma, at any tone (material-color-utilities' TonalPalette, without its key colour and cache). */
public final class TonalPalette {
  private final double hue;
  private final double chroma;

  private TonalPalette(double hue, double chroma) {
    this.hue = hue;
    this.chroma = chroma;
  }

  public static TonalPalette fromHueAndChroma(double hue, double chroma) {
    return new TonalPalette(hue, chroma);
  }

  public int tone(int tone) {
    return Hct.solveToInt(hue, chroma, tone);
  }

  public double getHue() { return hue; }
  public double getChroma() { return chroma; }
}
