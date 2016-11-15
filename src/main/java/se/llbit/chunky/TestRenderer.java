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

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import se.llbit.chunky.resources.MinecraftFinder;
import se.llbit.chunky.resources.TexturePackLoader;

import java.io.FileNotFoundException;
import java.net.URL;
import java.util.ResourceBundle;

public class TestRenderer extends Application implements Initializable {

  private final TestRenderThread renderThread;
  private double mouseX;
  private double mouseY;

  private final Object drawLock = new Object();
  private volatile boolean drawing = false;

  @FXML private Canvas canvas;
  @FXML private CheckBox showCompass;
  @FXML private TextField blockId;
  @FXML private TextField dataField;
  @FXML private ComboBox<String> model;
  @FXML private Label frameTime;

  public TestRenderer() throws FileNotFoundException, TexturePackLoader.TextureLoadingError {
    TexturePackLoader.loadTexturePack(MinecraftFinder.getMinecraftJarNonNull(), false);

    renderThread = new TestRenderThread(this, 400, 400);
  }

  public static void main(String[] args) {
    launch();
  }

  @Override public void start(Stage stage) throws Exception {
    FXMLLoader loader = new FXMLLoader(getClass().getResource("TestRender.fxml"));
    loader.setController(this);
    Parent root = loader.load();
    stage.setScene(new Scene(root));
    stage.setTitle("Test Renderer");
    stage.show();
    stage.setOnHiding(event -> renderThread.interrupt());
    renderThread.start();

    stage.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
      switch (event.getCode()) {
        case W:
          renderThread.moveForward(1);
          break;
        case S:
          renderThread.moveForward(-1);
          break;
        case ESCAPE:
          event.consume();
          stage.hide();
          break;
      }
    });

    canvas.setOnMousePressed(event -> {
      mouseX = event.getX();
      mouseY = event.getY();
    });

    canvas.setOnMouseDragged(event -> {
      double dx = event.getX() - mouseX;
      double dy = event.getY() - mouseY;
      mouseX = event.getX();
      mouseY = event.getY();
      renderThread.panView(dx, dy);
    });

    canvas.setOnScroll(event ->
        renderThread.moveForward(event.getDeltaY() / event.getMultiplierY()));
  }

  @Override public void initialize(URL location, ResourceBundle resources) {
    showCompass.selectedProperty().addListener(
        (observable, oldValue, newValue) -> renderThread.enableCompass(newValue));
    blockId.setText("" + renderThread.getBlockId());
    blockId.textProperty().addListener((observable, oldValue, newValue) -> {
      try {
        renderThread.setBlockId(Integer.parseInt(newValue));
      } catch (NumberFormatException ignored) {
      }
    });
    dataField.setText("0");
    dataField.textProperty().addListener((observable, oldValue, newValue) -> {
      try {
        renderThread.setBlockData(Integer.parseInt(newValue));
      } catch (NumberFormatException ignored) {
      }
    });
    model.getItems().addAll("block", "sprite", "custom");
    model.getSelectionModel().select("block");
    model.getSelectionModel().selectedItemProperty()
        .addListener((observable, oldValue, newValue) -> renderThread.setModel(newValue));
  }

  void drawImage(Image image, double time) {
    synchronized (drawLock) {
      if (!drawing) {
        drawing = true;
        Platform.runLater(() -> {
          // Synchronize to image to ensure we are not drawing it while its contents are changing.
          synchronized (image) {
            canvas.getGraphicsContext2D().drawImage(image, 0, 0);
          }
          frameTime.setText(String.format("%.1fms", time));
          if (time > 50) {
            System.out.format("Frame time: %.1fms%n", time);
          }
          drawing = false;
        });
      }
    }
  }
}
