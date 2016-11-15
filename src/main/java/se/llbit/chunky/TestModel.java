/*
 * Copyright (c) 2016 Jesper Öqvist <jesper@llbit.se>
 *
 * Chunky is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Chunky is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Chunky.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.llbit.chunky;

import se.llbit.chunky.model.Model;
import se.llbit.chunky.resources.Texture;
import se.llbit.math.Quad;
import se.llbit.math.Ray;
import se.llbit.math.Vector3;
import se.llbit.math.Vector4;

public class TestModel {
  private Quad[][] faces;
  private Texture[] textures;

  public TestModel() {
    faces = new Quad[6][];
  }

  void setUp() {
    textures = new Texture[] {
        Texture.commandBlockSide,
        Texture.commandBlockSide,
        Texture.commandBlockSide,
        Texture.commandBlockSide,
        Texture.commandBlockFront,
        Texture.commandBlockBack,
    };
    // Facing up:
    faces[1] = new Quad[] {
        // North face.
        new Quad(new Vector3(1, 0, 0), new Vector3(0, 0, 0), new Vector3(1, 1, 0),
            new Vector4(1, 0, 0, 1)),

        // South face.
        new Quad(new Vector3(0, 0, 1), new Vector3(1, 0, 1), new Vector3(0, 1, 1),
            new Vector4(0, 1, 0, 1)),

        // West face.
        new Quad(new Vector3(0, 0, 0), new Vector3(0, 0, 1), new Vector3(0, 1, 0),
            new Vector4(0, 1, 0, 1)),

        // East face.
        new Quad(new Vector3(1, 0, 1), new Vector3(1, 0, 0), new Vector3(1, 1, 1),
            new Vector4(1, 0, 0, 1)),

        // Top face.
        new Quad(new Vector3(1, 1, 0), new Vector3(0, 1, 0), new Vector3(1, 1, 1),
            new Vector4(1, 0, 0, 1)),

        // Bottom face.
        new Quad(new Vector3(0, 0, 0), new Vector3(1, 0, 0), new Vector3(0, 0, 1),
            new Vector4(0, 1, 0, 1)),

    };
    // Facing south:
    faces[3] = Model.rotateX(faces[1]);
    // Facing down:
    faces[0] = Model.rotateX(faces[3]);
    // Facing north:
    faces[2] = Model.rotateX(faces[0]);
    // Facing west:
    faces[4] = Model.rotateZ(faces[1]);
    // Facing east:
    faces[5] = Model.rotateZ(faces[0]);
  }

  public void intersect(Ray ray) {
    boolean hit = false;
    int direction = ray.getBlockData() % 6;
    for (int i = 0; i < 6; ++i) {
      Quad face = faces[direction][i];
      if (face.intersect(ray)) {
        textures[i].getColor(ray);
        ray.n.set(face.n);
        ray.t = ray.tNext;
        hit = true;
      }
    }
    if (hit) {
      ray.color.w = 1;
      ray.o.scaleAdd(ray.t, ray.d);
    }
  }
}
