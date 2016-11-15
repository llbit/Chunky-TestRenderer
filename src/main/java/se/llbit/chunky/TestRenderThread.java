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
import se.llbit.chunky.resources.MinecraftFinder;
import se.llbit.chunky.resources.Texture;
import se.llbit.chunky.resources.TexturePackLoader;
import se.llbit.chunky.resources.texturepack.SimpleTexture;
import se.llbit.chunky.resources.texturepack.TextureRef;
import se.llbit.chunky.world.Block;
import se.llbit.chunky.world.BlockData;
import se.llbit.math.ColorUtil;
import se.llbit.math.Matrix3;
import se.llbit.math.Quad;
import se.llbit.math.QuickMath;
import se.llbit.math.Ray;
import se.llbit.math.Vector3;
import se.llbit.math.Vector4;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

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

  // TODO: handle canvas resizing.
  private final int width;
  private final int height;
  private BitmapImage buffer;
  private BitmapImage backBuffer;

  private final Texture ironSword = new Texture();

  private TestModel testModel = new TestModel();

  private boolean drawCompass = false;
  private boolean drawCompassNext = false;

  private int blockData = 0;
  private int blockDataNext = 0;

  private int blockId = Block.GRASS_ID;
  private int blockIdNext = Block.GRASS_ID;

  private String model = "block";
  private String modelNext = "block";

  private double yaw, pitch;
  private boolean refresh = true;
  private final Vector3 camPos = new Vector3();
  private final double fov = 70;
  private final double fovTan = Camera.clampedFovTan(fov);
  private final Matrix3 nextTransform = new Matrix3();
  private final Matrix3 transform = new Matrix3();
  private double distance;
  private double nextDistance = 1.5;

  private static final Texture[] compassTexture = {
      new Texture("east"), new Texture("west"), new Texture("north"), new Texture("south")
  };

  private final Quad[] compassQuads = {
      new Quad(new Vector3(1, 0, 0), new Vector3(1, 0, 1), new Vector3(1, 1, 0),
          new Vector4(0, 1, 0, 1)),
      new Quad(new Vector3(0, 0, 1), new Vector3(0, 0, 0), new Vector3(0, 1, 1),
          new Vector4(0, 1, 0, 1)),
      new Quad(new Vector3(0, 0, 0), new Vector3(1, 0, 0), new Vector3(0, 1, 0),
          new Vector4(0, 1, 0, 1)),
      new Quad(new Vector3(1, 0, 1), new Vector3(0, 0, 1), new Vector3(1, 1, 1),
          new Vector4(0, 1, 0, 1))
  };

  public TestRenderThread(TestRenderer testRenderer, int width, int height) {
    this.testRenderer = testRenderer;
    this.width = width;
    this.height = height;

    Map<String, TextureRef> textures = new HashMap<>();
    textures.put("iron_sword", new SimpleTexture("assets/minecraft/textures/items/iron_sword",
        ironSword));
    TexturePackLoader.loadTextures(MinecraftFinder.getMinecraftJar(), textures.entrySet(),
        () -> {});

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
          blockData = blockDataNext;
          model = modelNext;
          testModel.setUp();
        }

        long time;
        synchronized (renderLock) {
          long start = System.nanoTime();

          drawFrame();

          time = System.nanoTime() - start;

          // Flip buffers.
          BitmapImage tmp = backBuffer;
          backBuffer = buffer;
          buffer = tmp;
          synchronized (image) {
            image.getPixelWriter()
                .setPixels(0, 0, width, height, PIXEL_FORMAT, buffer.data, 0, width);
          }
        }
        testRenderer.drawImage(image, time / 1000000.0);
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
        ray.t = Double.POSITIVE_INFINITY;
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

    switch (model) {
      case "block":
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
          ray.setCurrentMaterial(theBlock, blockId | (blockData << BlockData.OFFSET));
          theBlock.intersect(ray, scene);
        }
        break;
      case "sprite":
        if (drawCompass) {
          renderCompass(ray);
        }
        spriteIntersection(ray, ironSword);
        break;
      case "custom":
        if (tNear <= tFar && tFar >= 0) {
          if (tNear > 0) {
            ray.o.scaleAdd(tNear, ray.d);
            ray.distance += tNear;
          }

          if (drawCompass) {
            renderCompass(ray);
          }

          ray.setPrevMaterial(Block.AIR, 0);
          ray.setCurrentMaterial(Block.get(blockId), blockId | (blockData << BlockData.OFFSET));
          testModel.intersect(ray);
        }
        break;
    }
  }

  public boolean spriteIntersection(Ray ray, Texture texture) {
    double ox = ray.o.x;
    double oy = ray.o.y;
    double oz = ray.o.z;
    double offsetX = 0.5;
    double offsetY = 0.5;
    double offsetZ = 0.5;
    double inv_size = 16;
    double cloudTop = offsetY + 1 / inv_size;
    double t_offset = 0;
    if (oy < offsetY || oy > cloudTop) {
      if (ray.d.y > 0) {
        t_offset = (offsetY - oy) / ray.d.y;
      } else {
        t_offset = (cloudTop - oy) / ray.d.y;
      }
      if (t_offset < 0) {
        return false;
      }
      // Ray is entering the sprite.
      double x0 = (ray.d.x * t_offset + ox) * inv_size + offsetX;
      double z0 = (ray.d.z * t_offset + oz) * inv_size + offsetZ;
      if (inSprite(texture, x0, z0)) {
        ray.n.set(0, -Math.signum(ray.d.y), 0);
        ray.color.set(getColor(texture, (int) Math.floor(x0), (int) Math.floor(z0)));
        onSpriteEnter(ray, t_offset);
        return true;
      }
    } else if (inSprite(texture, ox * inv_size + offsetX, oz * inv_size + offsetZ)) {
      // We are inside the sprite - no intersection.
      return false;
    }
    double tExit;
    if (ray.d.y > 0) {
      tExit = (cloudTop - oy) / ray.d.y - t_offset;
    } else {
      tExit = (offsetY - oy) / ray.d.y - t_offset;
    }
    if (ray.t < tExit) {
      tExit = ray.t;
    }
    double x0 = (ox + ray.d.x * t_offset) * inv_size + offsetX;
    double z0 = (oz + ray.d.z * t_offset) * inv_size + offsetZ;
    double xp = x0;
    double zp = z0;
    int ix = (int) Math.floor(xp);
    int iz = (int) Math.floor(zp);
    int xmod = (int) Math.signum(ray.d.x), zmod = (int) Math.signum(ray.d.z);
    int xo = (1 + xmod) / 2, zo = (1 + zmod) / 2;
    double dx = Math.abs(ray.d.x) * inv_size;
    double dz = Math.abs(ray.d.z) * inv_size;
    double t = 0;
    int i = 0;
    int nx = 0, nz = 0;
    if (dx > dz) {
      double m = dz / dx;
      double xrem = xmod * (ix + xo - xp);
      double zlimit = xrem * m;
      while (t < tExit) {
        double zrem = zmod * (iz + zo - zp);
        if (zrem < zlimit) {
          iz += zmod;
          if (inSprite(texture, ix, iz)) {
            t = i / dx + zrem / dz;
            nx = 0;
            nz = -zmod;
            break;
          }
          ix += xmod;
          if (inSprite(texture, ix, iz)) {
            t = (i + xrem) / dx;
            nx = -xmod;
            nz = 0;
            break;
          }
        } else {
          ix += xmod;
          if (inSprite(texture, ix, iz)) {
            t = (i + xrem) / dx;
            nx = -xmod;
            nz = 0;
            break;
          }
          if (zrem <= m) {
            iz += zmod;
            if (inSprite(texture, ix, iz)) {
              t = i / dx + zrem / dz;
              nx = 0;
              nz = -zmod;
              break;
            }
          }
        }
        t = i / dx;
        i += 1;
        zp = z0 + zmod * i * m;
      }
    } else {
      double m = dx / dz;
      double zrem = zmod * (iz + zo - zp);
      double xlimit = zrem * m;
      while (t < tExit) {
        double xrem = xmod * (ix + xo - xp);
        if (xrem < xlimit) {
          ix += xmod;
          if (inSprite(texture, ix, iz)) {
            t = i / dz + xrem / dx;
            nx = -xmod;
            nz = 0;
            break;
          }
          iz += zmod;
          if (inSprite(texture, ix, iz)) {
            t = (i + zrem) / dz;
            nx = 0;
            nz = -zmod;
            break;
          }
        } else {
          iz += zmod;
          if (inSprite(texture, ix, iz)) {
            t = (i + zrem) / dz;
            nx = 0;
            nz = -zmod;
            break;
          }
          if (xrem <= m) {
            ix += xmod;
            if (inSprite(texture, ix, iz)) {
              t = i / dz + xrem / dx;
              nx = -xmod;
              nz = 0;
              break;
            }
          }
        }
        t = i / dz;
        i += 1;
        xp = x0 + xmod * i * m;
      }
    }
    int ny = 0;
    if (t > tExit) {
      return false;
    }
    ray.n.set(nx, ny, nz);
    // Side intersection.
    ray.color.set(getColor(texture, ix, iz));
    onSpriteEnter(ray, t + t_offset);
    return true;
  }

  private static void onSpriteEnter(Ray ray, double t) {
    ray.t = t;
    ray.o.scaleAdd(t, ray.d);
    ray.setPrevMaterial(Block.AIR, 0);
    ray.setCurrentMaterial(Block.get(Block.STONE_ID), 0);
  }

  private static boolean inSprite(Texture texture, double x, double z) {
    return inSprite(texture, (int) Math.floor(x), (int) Math.floor(z));
  }

  private static boolean inSprite(Texture texture, int x, int z) {
    if (x < 0 || x >= texture.getWidth() || z < 0 || z >= texture.getHeight()) {
      return false;
    }
    float[] color = texture.getColor(x, z);
    return color[3] != 0;
  }

  private static float[] getColor(Texture texture, int x, int z) {
    if (x < 0 || x >= texture.getWidth() || z < 0 || z >= texture.getHeight()) {
      throw new Error("Can't compute texture color");
    }
    return texture.getColor(x, z);
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

  public void setBlockData(int data) {
    synchronized (stateLock) {
      if (blockDataNext != data) {
        blockDataNext = data;
        refresh();
      }
    }
  }

  private void renderCompass(Ray ray) {
    ray.t = Double.POSITIVE_INFINITY;
    for (int i = 0; i < compassQuads.length; ++i) {
      if (compassQuads[i].intersect(ray)) {
        ray.t = ray.tNext;
        compassTexture[i].getColor(ray);
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

  public int getBlockId() {
    synchronized (stateLock) {
      return blockId;
    }
  }

  public void setModel(String model) {
    synchronized (stateLock) {
      modelNext = model;
      refresh();
    }
  }
}
