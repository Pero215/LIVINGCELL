// AnimatedOrganism3D.java
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Point3D;
import javafx.scene.*;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Sphere;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AnimatedOrganism3D extends Application {

    static final double SIM_MS_PER_DAY = 60000; // 1 min = 1 day
    static final int INITIAL_CELLS = 1;
    static final int SPACE_SIZE = 800;

    Group world3D = new Group();
    List<Cell> cells = new ArrayList<>();
    Octree octree;

    Random random = new Random();

    @Override
    public void start(Stage primaryStage) {

        // 3D Subscene
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-1000);

        SubScene subScene = new SubScene(world3D, 1000, 800, true, SceneAntialiasing.BALANCED);
        subScene.setCamera(camera);
        subScene.setFill(Color.BLACK);

        // 2D overlay for README
        TextArea readmeArea = new TextArea();
        readmeArea.setText(getREADME());
        readmeArea.setFont(Font.font("Monospaced", 14));
        readmeArea.setPrefHeight(200);
        readmeArea.setEditable(false);

        BorderPane root = new BorderPane();
        root.setCenter(subScene);
        root.setBottom(readmeArea);

        Scene scene = new Scene(root, 1000, 1000, true);

        primaryStage.setTitle("3D Living Cell Simulation");
        primaryStage.setScene(scene);
        primaryStage.show();

        // Initial cell
        Cell firstCell = new Cell(randomPosition(), 10, Color.LIME);
        cells.add(firstCell);
        world3D.getChildren().add(firstCell.view);

        // Initialize octree
        octree = new Octree(0, 0, 0, SPACE_SIZE);

        // Main simulation loop
        AnimationTimer timer = new AnimationTimer() {
            long lastUpdate = 0;
            @Override
            public void handle(long now) {
                if (lastUpdate == 0) lastUpdate = now;
                if ((now - lastUpdate) / 1_000_000 >= 16) { // ~60 FPS
                    updateSimulation();
                    lastUpdate = now;
                }
            }
        };
        timer.start();
    }

    void updateSimulation() {
        octree.clear();
        for (Cell c : cells) octree.insert(c);

        List<Cell> newCells = new ArrayList<>();
        for (Cell c : cells) {
            c.think(cells, octree);
            c.update();
            if (c.readyToDivide()) {
                Cell child = c.divide();
                newCells.add(child);
                world3D.getChildren().add(child.view);
            }
        }
        cells.addAll(newCells);

        // Simple absorption: bigger absorbs smaller if close
        for (int i = 0; i < cells.size(); i++) {
            Cell a = cells.get(i);
            for (int j = i + 1; j < cells.size(); j++) {
                Cell b = cells.get(j);
                if (a.isPredatoryMood() && a.distanceTo(b) < a.radius + b.radius) {
                    if (a.radius > b.radius) {
                        a.absorb(b);
                        world3D.getChildren().remove(b.view);
                        cells.remove(j);
                        j--;
                    }
                }
            }
        }
    }

    Point3D randomPosition() {
        return new Point3D(
                random.nextDouble() * SPACE_SIZE - SPACE_SIZE/2,
                random.nextDouble() * SPACE_SIZE - SPACE_SIZE/2,
                random.nextDouble() * SPACE_SIZE - SPACE_SIZE/2
        );
    }

    String getREADME() {
        return """
                3D Living Cell Simulation
                ------------------------
                - 1 minute = 1 day
                - Cells have moods: Neutral, Reproductive, Predatory
                - Cells can divide, move, absorb smaller cells
                - Cells make decisions using a simple neural-like brain
                - Octree spatial partitioning optimizes neighbor checks
                - Low-poly 3D visualization
                """;
    }

    public static void main(String[] args) {
        launch(args);
    }

    // --- Classes ---

    class Cell {
        Point3D position;
        double radius;
        Color color;
        Sphere view;

        double energy = 100;
        Mood mood = Mood.NEUTRAL;

        Random rand = new Random();

        Cell(Point3D pos, double radius, Color color) {
            this.position = pos;
            this.radius = radius;
            this.color = color;
            this.view = new Sphere(radius);
            PhongMaterial mat = new PhongMaterial(color);
            view.setMaterial(mat);
            view.setTranslateX(pos.getX());
            view.setTranslateY(pos.getY());
            view.setTranslateZ(pos.getZ());
        }

        void think(List<Cell> allCells, Octree octree) {
            // Simple "neural" brain
            List<Cell> neighbors = octree.query(this, radius*5);
            double avgEnergy = neighbors.stream().mapToDouble(c -> c.energy).average().orElse(energy);

            if (energy > 120) mood = Mood.REPRODUCTIVE;
            else if (neighbors.size() < 2) mood = Mood.PREDATORY;
            else mood = Mood.NEUTRAL;

            // Movement based on mood
            if (mood == Mood.PREDATORY) {
                Cell prey = neighbors.stream().filter(c -> c.radius < radius).findAny().orElse(null);
                if (prey != null) moveTowards(prey.position);
            } else if (mood == Mood.REPRODUCTIVE) {
                wander();
            } else {
                wander();
            }
        }

        void update() {
            energy -= 0.1;
            view.setTranslateX(position.getX());
            view.setTranslateY(position.getY());
            view.setTranslateZ(position.getZ());
        }

        boolean readyToDivide() {
            return energy > 150;
        }

        Cell divide() {
            energy /= 2;
            double offset = radius;
            Point3D childPos = position.add(rand.nextDouble()*offset, rand.nextDouble()*offset, rand.nextDouble()*offset);
            Cell child = new Cell(childPos, radius, color);
            child.energy = energy;
            return child;
        }

        void moveTowards(Point3D target) {
            double dx = (target.getX() - position.getX())*0.05;
            double dy = (target.getY() - position.getY())*0.05;
            double dz = (target.getZ() - position.getZ())*0.05;
            position = position.add(dx, dy, dz);
        }

        void wander() {
            double dx = (rand.nextDouble() - 0.5)*2;
            double dy = (rand.nextDouble() - 0.5)*2;
            double dz = (rand.nextDouble() - 0.5)*2;
            position = position.add(dx, dy, dz);
        }

        boolean isPredatoryMood() { return mood == Mood.PREDATORY; }

        double distanceTo(Cell other) {
            return position.distance(other.position);
        }

        void absorb(Cell other) {
            energy += other.energy;
            radius += other.radius * 0.2;
            view.setRadius(radius);
        }
    }

    enum Mood {
        NEUTRAL,
        REPRODUCTIVE,
        PREDATORY
    }

    // --- Octree for neighbor queries ---
    class Octree {
        final int MAX_OBJECTS = 8;
        final int MAX_LEVELS = 5;

        double x, y, z, size;
        int level;
        List<Cell> objects = new ArrayList<>();
        Octree[] nodes = null;

        Octree(double x, double y, double z, double size) {
            this(x, y, z, size, 0);
        }

        Octree(double x, double y, double z, double size, int level) {
            this.x = x; this.y = y; this.z = z; this.size = size; this.level = level;
        }

        void clear() {
            objects.clear();
            if (nodes != null) {
                for (Octree n : nodes) n.clear();
            }
            nodes = null;
        }

        void insert(Cell c) {
            if (nodes != null) {
                int index = getIndex(c);
                if (index != -1) {
                    nodes[index].insert(c);
                    return;
                }
            }
            objects.add(c);
            if (objects.size() > MAX_OBJECTS && level < MAX_LEVELS) {
                if (nodes == null) split();
                List<Cell> toReinsert = new ArrayList<>(objects);
                objects.clear();
                for (Cell oc : toReinsert) insert(oc);
            }
        }

        int getIndex(Cell c) {
            int index = -1;
            double midX = x + size/2;
            double midY = y + size/2;
            double midZ = z + size/2;

            boolean left = c.position.getX() < midX;
            boolean right = c.position.getX() >= midX;
            boolean top = c.position.getY() < midY;
            boolean bottom = c.position.getY() >= midY;
            boolean front = c.position.getZ() < midZ;
            boolean back = c.position.getZ() >= midZ;

            if (left && top && front) index = 0;
            else if (right && top && front) index = 1;
            else if (left && bottom && front) index = 2;
            else if (right && bottom && front) index = 3;
            else if (left && top && back) index = 4;
            else if (right && top && back) index = 5;
            else if (left && bottom && back) index = 6;
            else if (right && bottom && back) index = 7;

            return index;
        }

        void split() {
            nodes = new Octree[8];
            double newSize = size / 2;
            nodes[0] = new Octree(x, y, z, newSize, level+1);
            nodes[1] = new Octree(x+newSize, y, z, newSize, level+1);
            nodes[2] = new Octree(x, y+newSize, z, newSize, level+1);
            nodes[3] = new Octree(x+newSize, y+newSize, z, newSize, level+1);
            nodes[4] = new Octree(x, y, z+newSize, newSize, level+1);
            nodes[5] = new Octree(x+newSize, y, z+newSize, newSize, level+1);
            nodes[6] = new Octree(x, y+newSize, z+newSize, newSize, level+1);
            nodes[7] = new Octree(x+newSize, y+newSize, z+newSize, newSize, level+1);
        }

        List<Cell> query(Cell c, double range) {
            List<Cell> result = new ArrayList<>();
            queryHelper(c, range, result);
            return result;
        }

        void queryHelper(Cell c, double range, List<Cell> result) {
            for (Cell oc : objects) {
                if (oc != c && c.distanceTo(oc) <= range) result.add(oc);
            }
            if (nodes != null) {
                for (Octree n : nodes) n.queryHelper(c, range, result);
            }
        }
    }
}
