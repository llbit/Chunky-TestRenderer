/* Copyright (c) 2016 Jesper Öqvist <jesper@llbit.se>
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

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import org.apache.commons.math3.util.FastMath;
import se.llbit.chunky.renderer.scene.Camera;
import se.llbit.chunky.resources.BitmapImage;
import se.llbit.chunky.resources.Texture;
import se.llbit.chunky.world.Block;
import se.llbit.math.ColorUtil;
import se.llbit.math.Matrix3;
import se.llbit.math.Quad;
import se.llbit.math.QuickMath;
import se.llbit.math.Ray;
import se.llbit.math.Vector3;
import se.llbit.math.Vector4;

import java.nio.IntBuffer;

class TestRenderThread extends Thread {
  private final WritablePixelFormat<IntBuffer> PIXEL_FORMAT = PixelFormat.getIntArgbInstance();

  private TestRenderer testRenderer;

  /**
   * Mock scene object required by some block renderers.
   */
  private final se.llbit.chunky.renderer.scene.Scene scene;

  /**
   * This lock is held whenever changes are made to the scene state.
   */
  private final Object stateLock = new Object();

  private final Object renderLock = new Object();

  private final WritableImage image;

  private BitmapImage buffer;
  private BitmapImage backBuffer;
  private final int width = 400;
  private final int height = 400;

  private boolean drawCompass = false;
  private boolean drawCompassNext = false;

  private int blockId = Block.GRASS_ID;
  private int blockIdNext = Block.GRASS_ID;

  private double yaw, pitch;
  private boolean refresh = true;
  private final Vector3 camPos = new Vector3();
  private final double fov = 70;
  private final double fovTan = Camera.clampedFovTan(fov);
  private final Matrix3 nextTransform = new Matrix3();
  private final Matrix3 transform = new Matrix3();
  private double distance;
  private double nextDistance = 1.5;

  private static final Texture[] tex =
      {new Texture("east"), new Texture("west"), new Texture("north"), new Texture("south"),};

  private final Quad[] quads =
      {new Quad(new Vector3(1, 0, 0), new Vector3(1, 0, 1), new Vector3(1, 1, 0),
          new Vector4(0, 1, 0, 1)),
          new Quad(new Vector3(0, 0, 1), new Vector3(0, 0, 0), new Vector3(0, 1, 1),
              new Vector4(0, 1, 0, 1)),
          new Quad(new Vector3(0, 0, 0), new Vector3(1, 0, 0), new Vector3(0, 1, 0),
              new Vector4(0, 1, 0, 1)),
          new Quad(new Vector3(1, 0, 1), new Vector3(0, 0, 1), new Vector3(1, 1, 1),
              new Vector4(0, 1, 0, 1)),};

  public TestRenderThread(TestRenderer testRenderer) {
    this.testRenderer = testRenderer;

    // Initialize render buffers.
    buffer = new BitmapImage(width, height);
    backBuffer = new BitmapImage(width, height);
    image = new WritableImage(width, height);

    // Create mock scene object.
    scene = new se.llbit.chunky.renderer.scene.Scene();
    scene.setBiomeColorsEnabled(false);

    // Initialize camera:
    yaw = -3 * Math.PI / 4;
    pitch = -5 * Math.PI / 6;
    updateTransform();
  }

  @Override public void run() {

    try {
      while (!isInterrupted()) {

        synchronized (stateLock) {
          awaitRefresh();
          transform.set(nextTransform);
          distance = nextDistance;
          drawCompass = drawCompassNext;
          blockId = blockIdNext;
        }

        synchronized (renderLock) {
          drawFrame();

          // Flip buffers.
          BitmapImage tmp = backBuffer;
          backBuffer = buffer;
          buffer = tmp;
          synchronized (image) {
            image.getPixelWriter()
                .setPixels(0, 0, width, height, PIXEL_FORMAT, buffer.data, 0, width);
          }
        }
        testRenderer.drawImage(image);
      }
    } catch (InterruptedException ignored) {
    }
  }

  private void drawFrame() {

    double aspect = width / (double) height;

    Ray ray = new Ray();

    camPos.set(0, -distance, 0);
    transform.transform(camPos);
    camPos.add(.5, .5, .5);

    for (int y = 0; y < height; ++y) {

      double rayZ = fovTan * (.5 - ((double) y) / height);

      for (int x = 0; x < width; ++x) {
        double rayX = fovTan * aspect * (.5 - ((double) x) / width);

        ray.setDefault();
        ray.d.set(rayX, 1, rayZ);
        ray.d.normalize();
        transform.transform(ray.d);

        ray.o.set(camPos);
        trace(ray);

        ray.color.x = QuickMath.min(1, FastMath.sqrt(ray.color.x));
        ray.color.y = QuickMath.min(1, FastMath.sqrt(ray.color.y));
        ray.color.z = QuickMath.min(1, FastMath.sqrt(ray.color.z));
        backBuffer.setPixel(x, y, ColorUtil.getRGB(ray.color));
      }
    }
  }

  private void trace(Ray ray) {
    double[] nearFar = new double[2];
    enterBlock(ray, nearFar);
    double tNear = nearFar[0];
    double tFar = nearFar[1];

    ray.color.set(1, 1, 1, 1);

    if (tNear <= tFar && tFar >= 0) {
      if (tNear > 0) {
        ray.o.scaleAdd(tNear, ray.d);
        ray.distance += tNear;
      }

      if (drawCompass) {
        renderCompass(ray);
      }

      ray.setPrevMaterial(Block.AIR, 0);
      Block theBlock = Block.get(blockId);
      ray.setCurrentMaterial(theBlock, blockId);
      theBlock.intersect(ray, scene);
    }
  }

  private void awaitRefresh() throws InterruptedException {
    synchronized (stateLock) {
      while (!refresh) {
        stateLock.wait();
      }
      refresh = false;
    }
  }

  private void updateTransform() {
    Matrix3 tmpTransform = new Matrix3();

    nextTransform.setIdentity();

    // Yaw (y axis rotation).
    tmpTransform.rotY(QuickMath.HALF_PI + yaw);
    nextTransform.mul(tmpTransform);

    // Pitch (x axis rotation).
    tmpTransform.rotX(QuickMath.HALF_PI - pitch);
    nextTransform.mul(tmpTransform);
  }

  public void moveForward(double scale) {
    synchronized (stateLock) {
      nextDistance -= .1 * scale;
      nextDistance = QuickMath.max(.1, nextDistance);
    }
    refresh();
  }

  public void refresh() {
    synchronized (stateLock) {
      refresh = true;
      stateLock.notifyAll();
    }
  }

  public void panView(double dx, double dy) {
    synchronized (stateLock) {
      double fovRad = QuickMath.degToRad(fov / 2);

      yaw += (Math.PI / 250) * dx * fovRad;
      pitch += (Math.PI / 250) * dy * fovRad;

      if (yaw > QuickMath.TAU) {
        yaw -= QuickMath.TAU;
      } else if (yaw < -QuickMath.TAU) {
        yaw += QuickMath.TAU;
      }

      updateTransform();
    }
    refresh();
  }

  public void enableCompass(boolean enable) {
    synchronized (stateLock) {
      if (drawCompassNext != enable) {
        drawCompassNext = enable;
        refresh();
      }
    }
  }

  public void setBlockId(int blockId) {
    synchronized (stateLock) {
      if (blockIdNext != blockId) {
        blockIdNext = blockId;
        refresh();
      }
    }
  }

  private void renderCompass(Ray ray) {
    ray.t = Double.POSITIVE_INFINITY;
    for (int i = 0; i < quads.length; ++i) {
      if (quads[i].intersect(ray)) {
        ray.t = ray.tNext;
        tex[i].getColor(ray);
      }
    }
  }

  /**
   * Advance the ray until it enters the center voxel.
   */
  private void enterBlock(Ray ray, double[] nearFar) {
    int level = 0;
    double t1, t2;
    double tNear = Double.NEGATIVE_INFINITY;
    double tFar = Double.POSITIVE_INFINITY;
    Vector3 d = ray.d;
    Vector3 o = ray.o;

    if (d.x != 0) {
      t1 = -o.x / d.x;
      t2 = ((1 << level) - o.x) / d.x;

      if (t1 > t2) {
        double t = t1;
        t1 = t2;
        t2 = t;
      }

      if (t1 > tNear) {
        tNear = t1;
      }
      if (t2 < tFar) {
        tFar = t2;
      }
    }

    if (d.y != 0) {
      t1 = -o.y / d.y;
      t2 = ((1 << level) - o.y) / d.y;

      if (t1 > t2) {
        double t = t1;
        t1 = t2;
        t2 = t;
      }

      if (t1 > tNear) {
        tNear = t1;
      }
      if (t2 < tFar) {
        tFar = t2;
      }
    }

    if (d.z != 0) {
      t1 = -o.z / d.z;
      t2 = ((1 << level) - o.z) / d.z;

      if (t1 > t2) {
        double t = t1;
        t1 = t2;
        t2 = t;
      }

      if (t1 > tNear) {
        tNear = t1;
      }
      if (t2 < tFar) {
        tFar = t2;
      }
    }

    nearFar[0] = tNear;
    nearFar[1] = tFar;
  }

}
