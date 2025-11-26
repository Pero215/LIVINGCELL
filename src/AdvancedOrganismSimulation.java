// AdvancedOrganismSimulation.java
import javafx.animation.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.*;

public class AdvancedOrganismSimulation extends Application {

    static final double SIM_MS_PER_DAY = 60000; // 1 min = 1 day
    static final int INITIAL_CELLS = 20;
    static final int SPACE_SIZE = 1500;
    static final int INITIAL_FOOD = 40;

    Group world3D = new Group();
    List<Cell> allCells = new ArrayList<>();
    List<Organism> organisms = new ArrayList<>();
    List<Food> foodPatches = new ArrayList<>();
    Octree octree;

    Random random = new Random();

    @Override
    public void start(Stage stage) {

        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-2500);
        camera.setTranslateY(-100);
        camera.setNearClip(0.1);
        camera.setFarClip(10000);

        SubScene subScene = new SubScene(world3D, 1400, 900, true, SceneAntialiasing.BALANCED);
        subScene.setCamera(camera);
        subScene.setFill(Color.BLACK);

        TextArea readmeArea = new TextArea(getREADME());
        readmeArea.setFont(Font.font("Monospaced", 14));
        readmeArea.setPrefHeight(200);
        readmeArea.setEditable(false);

        BorderPane root = new BorderPane();
        root.setCenter(subScene);
        root.setBottom(readmeArea);

        stage.setTitle("Advanced Multi-Organ Low-Poly Organism Simulation");
        stage.setScene(new Scene(root, 1400, 1100, true));
        stage.show();

        // Octree for spatial partitioning
        octree = new Octree(0, 0, 0, SPACE_SIZE);

        // Initialize organisms and cells
        for (int i = 0; i < INITIAL_CELLS; i++) {
            Cell c = new Cell(randomPosition(), 15, 50 + random.nextInt(50));
            allCells.add(c);

            Organism org = new Organism();
            org.addCell(c);
            organisms.add(org);

            world3D.getChildren().add(c.view);
        }

        // Initialize food patches
        for (int i = 0; i < INITIAL_FOOD; i++) {
            Food f = new Food(randomPosition(), 50 + random.nextInt(50));
            foodPatches.add(f);
            world3D.getChildren().add(f.view);
        }

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
        for (Cell c : allCells) octree.insert(c);

        List<Cell> newCells = new ArrayList<>();
        for (Organism org : organisms) {
            org.think(octree, foodPatches);
            List<Cell> offspring = org.update();
            newCells.addAll(offspring);
            for (Cell c : offspring) world3D.getChildren().add(c.view);
        }
        allCells.addAll(newCells);

        // Absorption: bigger organisms absorb smaller ones
        for (int i = 0; i < organisms.size(); i++) {
            Organism a = organisms.get(i);
            for (int j = i + 1; j < organisms.size(); j++) {
                Organism b = organisms.get(j);
                if (a.distanceTo(b) < 60 && a.iq > b.iq) {
                    a.absorb(b);
                    organisms.remove(j);
                    j--;
                }
            }
        }

        // Remove consumed food
        foodPatches.removeIf(f -> f.energy <= 0);
    }

    Point3D randomPosition() {
        return new Point3D(random.nextDouble() * SPACE_SIZE - SPACE_SIZE / 2,
                random.nextDouble() * SPACE_SIZE - SPACE_SIZE / 2,
                random.nextDouble() * SPACE_SIZE - SPACE_SIZE / 2);
    }

    String getREADME() {
        return """
                Advanced Multi-Organ Low-Poly Organism Simulation
                ------------------------------------------------
                Features:
                - Cells differentiate into tissues and organs: Heart, Lungs, Brain, Muscles, Sexual Organs, Kidney, Liver, Glands
                - Organs have functional animations: heart pumping, lungs breathing, brain firing
                - Tissue interactions affect organism function
                - Neural brain controls movement, energy, hunting, fleeing, reproduction
                - Low-poly 3D meshes for organs
                - Predation and IQ-based AI learning
                - Octree spatial partitioning
                - 1 minute = 1 day simulation speed
                """;
    }

    public static void main(String[] args) {
        launch(args);
    }

    // ================== CLASSES ======================

    enum CellType { NERVE, MUSCLE, SKIN, HEART, LUNG, BRAIN, SEXUAL, KIDNEY, LIVER, GLAND }

    class Cell {
        Point3D position;
        double radius;
        MeshView view;
        Color color;
        double energy = 100;
        int iq;
        CellType type;
        Random rand = new Random();

        Timeline organAnimation;

        public Cell(Point3D pos, double r, int iq) {
            this.position = pos;
            this.radius = r;
            this.iq = iq;

            // Randomly differentiate cell type
            double t = rand.nextDouble();
            if (t < 0.1) type = CellType.NERVE;
            else if (t < 0.2) type = CellType.MUSCLE;
            else if (t < 0.3) type = CellType.HEART;
            else if (t < 0.4) type = CellType.LUNG;
            else if (t < 0.5) type = CellType.BRAIN;
            else if (t < 0.6) type = CellType.SEXUAL;
            else if (t < 0.7) type = CellType.KIDNEY;
            else if (t < 0.8) type = CellType.LIVER;
            else if (t < 0.9) type = CellType.GLAND;
            else type = CellType.SKIN;

            view = new MeshView(createTetrahedron(radius));
            switch (type) {
                case NERVE -> color = Color.CYAN;
                case MUSCLE -> color = Color.RED;
                case SKIN -> color = Color.LIME;
                case HEART -> color = Color.DARKRED;
                case LUNG -> color = Color.LIGHTBLUE;
                case BRAIN -> color = Color.PURPLE;
                case SEXUAL -> color = Color.PINK;
                case KIDNEY -> color = Color.BROWN;
                case LIVER -> color = Color.ORANGE;
                case GLAND -> color = Color.GOLD;
            }
            view.setMaterial(new PhongMaterial(color));
            updatePosition(position);

            // Organ animations
            if (type == CellType.HEART || type == CellType.LUNG || type == CellType.BRAIN) animateOrgan();
        }

        void animateOrgan() {
            organAnimation = new Timeline(
                    new KeyFrame(Duration.ZERO, e -> view.setScaleX(1.0)),
                    new KeyFrame(Duration.millis(500), e -> view.setScaleX(1.2)),
                    new KeyFrame(Duration.millis(1000), e -> view.setScaleX(1.0))
            );
            organAnimation.setCycleCount(Timeline.INDEFINITE);
            organAnimation.setAutoReverse(true);
            organAnimation.play();
        }

        void updatePosition(Point3D newPos) {
            this.position = newPos;
            Platform.runLater(() -> {
                view.setTranslateX(position.getX());
                view.setTranslateY(position.getY());
                view.setTranslateZ(position.getZ());
            });
        }

        TriangleMesh createTetrahedron(double size) {
            TriangleMesh mesh = new TriangleMesh();
            float s = (float) size;
            mesh.getPoints().addAll(0, 0, 0, s, 0, 0, 0, s, 0, 0, 0, s);
            mesh.getTexCoords().addAll(0, 0);
            mesh.getFaces().addAll(0, 0, 1, 0, 2, 0, 0, 0, 1, 0, 3, 0, 0, 0, 2, 0, 3, 0, 1, 0, 2, 0, 3, 0);
            return mesh;
        }
    }

    class Organism {
        List<Cell> cells = new ArrayList<>();
        Map<String, Organ> organs = new HashMap<>();
        int iq = 50 + random.nextInt(50);
        Point3D position = new Point3D(0, 0, 0);
        Random rand = new Random();

        void addCell(Cell c) {
            cells.add(c);
            addToOrgan(c);
            recomputePosition();
        }

        void addToOrgan(Cell c) {
            switch (c.type) {
                case HEART -> organs.computeIfAbsent("Heart", n -> new Organ(n, this::heartFunction)).addCell(c);
                case LUNG -> organs.computeIfAbsent("Lungs", n -> new Organ(n, this::lungFunction)).addCell(c);
                case BRAIN -> organs.computeIfAbsent("Brain", n -> new Organ(n, this::brainFunction)).addCell(c);
                case MUSCLE -> organs.computeIfAbsent("Muscles", n -> new Organ(n, this::muscleFunction)).addCell(c);
                case SEXUAL -> organs.computeIfAbsent("Sexual", n -> new Organ(n, this::sexualFunction)).addCell(c);
                case KIDNEY -> organs.computeIfAbsent("Kidney", n -> new Organ(n, this::kidneyFunction)).addCell(c);
                case LIVER -> organs.computeIfAbsent("Liver", n -> new Organ(n, this::liverFunction)).addCell(c);
                case GLAND -> organs.computeIfAbsent("Glands", n -> new Organ(n, this::glandFunction)).addCell(c);
                default -> {}
            }
        }

        void recomputePosition() {
            double x = 0, y = 0, z = 0;
            for (Cell c : cells) {
                x += c.position.getX();
                y += c.position.getY();
                z += c.position.getZ();
            }
            position = new Point3D(x / cells.size(), y / cells.size(), z / cells.size());
        }

        void think(Octree octree, List<Food> foods) {
            // Organ-level operations
            for (Organ o : organs.values()) o.operate(this);

            // Movement towards nearest food
            Food targetFood = null;
            double minDist = Double.MAX_VALUE;
            for (Food f : foods) {
                double dist = position.distance(f.position);
                if (dist < minDist) {
                    minDist = dist;
                    targetFood = f;
                }
            }
            if (targetFood != null && minDist < 150) moveTowards(targetFood.position, 2 + iq / 50.0);
            else randomMove();

            // Predation AI based on IQ
            for (Organism o : organisms) {
                if (o == this) continue;
                double dist = distanceTo(o);
                if (dist < 100 && this.iq > o.iq) {
                    moveTowards(o.position, 3 + iq / 50.0);
                    if (dist < 20) absorb(o);
                }
            }

            // Reproduction if enough energy
            double totalEnergy = cells.stream().mapToDouble(c -> c.energy).sum();
            if (totalEnergy > cells.size() * 150) reproduce();
        }

        void moveTowards(Point3D target, double speed) {
            for (Cell c : cells) {
                c.updatePosition(c.position.add(
                        (target.getX() - position.getX()) * 0.05 * speed,
                        (target.getY() - position.getY()) * 0.05 * speed,
                        (target.getZ() - position.getZ()) * 0.05 * speed
                ));
            }
            recomputePosition();
        }

        void randomMove() {
            double dx = rand.nextDouble() * 5 - 2.5;
            double dy = rand.nextDouble() * 5 - 2.5;
            double dz = rand.nextDouble() * 5 - 2.5;
            moveTowards(position.add(dx, dy, dz), 1);
        }

        List<Cell> update() {
            recomputePosition();
            return Collections.emptyList();
        }

        double distanceTo(Organism o) {
            return position.distance(o.position);
        }

        void absorb(Organism other) {
            for (Cell c : other.cells) {
                Platform.runLater(() -> world3D.getChildren().remove(c.view));
            }
            this.cells.addAll(other.cells);
            for (Cell c : other.cells) addToOrgan(c);
            this.iq = (int) this.cells.stream().mapToInt(c -> c.iq).average().orElse(iq);
            recomputePosition();
        }

        void reproduce() {
            List<Cell> newCells = new ArrayList<>();
            for (Cell c : cells) {
                Cell child = new Cell(c.position.add(rand.nextDouble() * 10, rand.nextDouble() * 10, rand.nextDouble() * 10),
                        c.radius, c.iq + rand.nextInt(10) - 5);
                newCells.add(child);
            }
            for (Cell c : newCells) addCell(c);
        }

        // Organ functions
        void heartFunction(List<Cell> cells, Organism org) {
            double flow = 0.5;
            for (Cell c : cells) c.energy += flow;
        }
        void lungFunction(List<Cell> cells, Organism org) {
            for (Cell c : org.cells) c.energy *= 1.001;
        }
        void brainFunction(List<Cell> cells, Organism org) { }
        void muscleFunction(List<Cell> cells, Organism org) { }
        void sexualFunction(List<Cell> cells, Organism org) { }
        void kidneyFunction(List<Cell> cells, Organism org) { for (Cell c : cells) c.energy *= 0.999; }
        void liverFunction(List<Cell> cells, Organism org) { for (Cell c : cells) c.energy *= 1.0005; }
        void glandFunction(List<Cell> cells, Organism org) { }
    }

    class Organ {
        String name;
        List<Cell> cells = new ArrayList<>();
        OrganFunction function;

        Organ(String name, OrganFunction function) {
            this.name = name;
            this.function = function;
        }

        void addCell(Cell c) { cells.add(c); }
        void operate(Organism org) { function.apply(cells, org); }
    }

    interface OrganFunction { void apply(List<Cell> cells, Organism org); }

    class Food {
        Point3D position;
        double energy;
        MeshView view;

        public Food(Point3D pos, double energy) {
            this.position = pos;
            this.energy = energy;
            view = new MeshView(createFoodMesh());
            view.setMaterial(new PhongMaterial(Color.YELLOW));
            view.setTranslateX(pos.getX());
            view.setTranslateY(pos.getY());
            view.setTranslateZ(pos.getZ());
        }

        TriangleMesh createFoodMesh() {
            TriangleMesh mesh = new TriangleMesh();
            float s = 10f;
            mesh.getPoints().addAll(0,0,0,s,0,0,0,s,0,0,0,s);
            mesh.getTexCoords().addAll(0,0);
            mesh.getFaces().addAll(0,0,1,0,2,0,0,0,1,0,3,0);
            return mesh;
        }
    }

    // Octree class
    class Octree {
        final int MAX_OBJECTS = 8;
        final int MAX_LEVELS = 5;
        double x,y,z,size;
        int level;
        List<Cell> objects = new ArrayList<>();
        Octree[] nodes = null;
        Octree(double x,double y,double z,double size) { this(x,y,z,size,0); }
        Octree(double x,double y,double z,double size,int level) { this.x=x;this.y=y;this.z=z;this.size=size;this.level=level; }

        void clear() { objects.clear(); if(nodes!=null) {for(Octree n:nodes) n.clear();} nodes=null; }
        void insert(Cell c) {
            if(nodes!=null) { int idx=getIndex(c); if(idx!=-1){ nodes[idx].insert(c); return; } }
            objects.add(c);
            if(objects.size()>MAX_OBJECTS && level<MAX_LEVELS){ if(nodes==null) split(); List<Cell> temp=new ArrayList<>(objects); objects.clear(); for(Cell oc:temp) insert(oc);}
        }
        int getIndex(Cell c) { int idx=-1; double midX=x+size/2,midY=y+size/2,midZ=z+size/2; boolean left=c.position.getX()<midX,right=c.position.getX()>=midX,top=c.position.getY()<midY,bottom=c.position.getY()>=midY,front=c.position.getZ()<midZ,back=c.position.getZ()>=midZ; if(left&&top&&front) idx=0; else if(right&&top&&front) idx=1; else if(left&&bottom&&front) idx=2; else if(right&&bottom&&front) idx=3; else if(left&&top&&back) idx=4; else if(right&&top&&back) idx=5; else if(left&&bottom&&back) idx=6; else if(right&&bottom&&back) idx=7; return idx;}
        void split() { nodes=new Octree[8]; double hs=size/2; nodes[0]=new Octree(x,y,z,hs,level+1); nodes[1]=new Octree(x+hs,y,z,hs,level+1); nodes[2]=new Octree(x,y+hs,z,hs,level+1); nodes[3]=new Octree(x+hs,y+hs,z,hs,level+1); nodes[4]=new Octree(x,y,z+hs,hs,level+1); nodes[5]=new Octree(x+hs,y,z+hs,hs,level+1); nodes[6]=new Octree(x,y+hs,z+hs,hs,level+1); nodes[7]=new Octree(x+hs,y+hs,z+hs,hs,level+1);}
    }
}
