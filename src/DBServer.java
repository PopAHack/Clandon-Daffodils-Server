import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class DBServer extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    //global vars
    final static int PORT_TCP = 40202;

    @Override
    public void start(final Stage stage) {
        try {
            //draw program UI
            Group root = new Group();
            Label title = new Label();
            Button buttonConnect = new Button();

            root.getChildren().add(title);
            root.getChildren().add(buttonConnect);

            Scene scene = new Scene(root, 600, 600, Color.BISQUE);
            title.setText("Clandon Daffodils Database Server");
            title.relocate(210, 10);
            buttonConnect.setText("Connect New Devices");
            buttonConnect.relocate(230, 50);

            stage.setTitle("Clandon Daffodils Database Server");
            stage.setScene(scene);
            stage.setResizable(false);
            stage.show();

            //create settings file if it doesn't exist already
            String curDir = System.getProperty("user.dir");
            File DV = new File(curDir, "Database Versions");


            DV.mkdir();
            File settingsFile = new File(curDir, "Settings");

            if(settingsFile.createNewFile()) {
                BufferedWriter settingsWriter = new BufferedWriter(new FileWriter(settingsFile));
                settingsWriter.flush();
                settingsWriter.write("EditableDeviceID=-1" + "\n");
                settingsWriter.write("NextDeviceID=1" + "\n");
                settingsWriter.close();
            }

            buttonConnect.setOnMouseClicked((event -> {
                sendIPAddress();
            }));

            stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
                @Override
                public void handle(WindowEvent event) {

                    Platform.exit();

                    Thread start = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            //TODO Auto-generated method stub
                            System.exit(0);
                        }
                    });

                    start.start();
                }
            });

            //set up server
            ServerSocket connection = new ServerSocket(PORT_TCP);
            System.out.println("Ready...");

            serverWaitThread waitThread = new serverWaitThread(connection);
            waitThread.start();
        } catch (Exception ex) {
            System.out.println(ex.toString());
        }
    }

    public void sendIPAddress()
    {
        try {
            //create background stuff
            String inetAddrUDP = "224.0.0.3";
            int portUDP = 8888;
            InetAddress address = InetAddress.getByName(inetAddrUDP);
            DatagramSocket serverSocket = new DatagramSocket();

            //send ready packet
            String msg = InetAddress.getLocalHost().getHostAddress();
            System.out.println(msg);
            DatagramPacket msgPacket = new DatagramPacket(msg.getBytes(), msg.getBytes().length, address, portUDP);

            serverSocket.send(msgPacket);
            serverSocket.close();

            System.out.println("IP address sent out of UDP socket successfully");
        }catch (Exception ex)
        {
            System.out.println(ex.toString());
        }
    }

    //thread that handles waiting for a connection
    public class serverWaitThread extends Thread {
        public serverWaitThread(ServerSocket ss) { this.ss = ss; }

        //global vars
        ServerSocket ss;

        @Override
        public void run() {
            try {
                while(true) {
                    System.out.println("Connection waiting");
                    MyThread connectionThread = new MyThread(ss.accept());
                    System.out.println("Connection started");
                    connectionThread.start();
                }
            } catch (Exception ex) {
                System.out.println(ex.toString());
            }
        }
    }

    //My thread designed to look after a devices request
    public class MyThread extends Thread {
        //constructor
        public MyThread(Socket connection) {
            this.connection = connection;
        }

        //global vars
        Socket connection;

        @Override
        public void run() {
            try {
                System.out.println("Request started");
                String curDir = System.getProperty("user.dir");
                File settingsFile = new File(curDir, "Settings");

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                DataOutputStream writer = new DataOutputStream(connection.getOutputStream());

                System.out.println("Reading in request...");
                String line = reader.readLine();

                String[] lineArray = line.split(",");
                System.out.println("Request recevied");

                BufferedReader rs = new BufferedReader(new FileReader(settingsFile.getPath()));
                System.out.println("reader ready: " + rs.ready());

                String line2 = rs.readLine();
                String[] settingsEditableID = line2.split("=");
                rs.close();
                System.out.println("Everything initiated, message request received");

                if(lineArray.length == 1)System.out.println("no id");

                if (Integer.parseInt(lineArray[1]) == -1) {
                    //if the device has no id, then generate one and return it.
                    int nextID = getNextDeviceID();
                    if(nextID == -2)
                    {
                        System.out.println("ERROR in getNextDeviceID method");
                        reader.close();
                        return;
                    }
                    writer.write((nextID + "\n").getBytes(), 0, new String(nextID + "\n").length());

                    lineArray[1] = String.valueOf(nextID);//lineArray[1] contains our devices ID
                    System.out.println("Generated and returned an ID for the device");
                }

                //if its a backup data button press
                if (lineArray[0].equals("push")) {
                    //check if the device pushing is the editable device
                    //the device CANNOT have an ID of -1
                    if (lineArray[1].equals(settingsEditableID[1])) {
                        writer.write(("Success" + "\n").getBytes(), 0, new String("Success" + "\n").length());
                        updateDatabase(reader);
                        System.out.println("Request successful, terminating.");
                    }
                    else {
                        //tell the device that they cannot edit the database
                        writer.write(("Read Only" + "\n").getBytes(), 0, new String("Read Only" + "\n").length());
                        System.out.println("Push failed, request terminated");
                        writer.close();
                        return;
                    }
                }

                //if its a request current version
                if (lineArray[0].equals("pull"))
                    sendLatestDatabase();
                if (lineArray[0].equals("editableRequest")) {
                    setEditableDevice(Integer.parseInt(lineArray[1]));
                    System.out.println("Request successful, terminating.");
                }

                reader.close();//closes both
                writer.close();

            } catch (Exception ex) {
                System.out.println(ex.toString());
            }
        }

        public int getNextDeviceID()
        {
            try {
                System.out.println("Starting method: getNextDeviceID()");
                String curDir = System.getProperty("user.dir");
                File settingsFile = new File(curDir, "Settings");
                Boolean foundID = false;
                int ID = -2;
                BufferedReader reader = new BufferedReader(new FileReader(settingsFile.getPath()));

                List<String> csvFile = new ArrayList<String>();
                String line;
                String[] lineArray;

                while ((line = reader.readLine()) != null) {
                    lineArray = line.split("=");
                    if (lineArray[0].equals("NextDeviceID")) {

                        csvFile.add(lineArray[0] + "=" + ((Integer.parseInt(lineArray[1])) + 1) + "\n");
                        System.out.println("Found next ID");

                        foundID = true;
                        ID = Integer.parseInt(lineArray[1]);
                    } else {
                        csvFile.add(lineArray[0] + "=" + lineArray[1] + "\n");
                        System.out.println("Copied over an irrelevant line");
                    }
                }
                reader.close();

                settingsFile.delete();
                settingsFile.createNewFile();
                System.out.println("Settings file wiped, ready for write");

                BufferedWriter writer = new BufferedWriter(new FileWriter(settingsFile.getPath()));
                //rewriting settings file with an incremented ID
                for(int i = 0; i < csvFile.size(); i++)
                    writer.write(csvFile.get(i));

                writer.close();


                if(foundID) {
                    System.out.println("Completed method successfully: getNextDeviceID()");
                    return ID;
                }
            }catch (Exception ex)
            {
                System.out.println(ex.toString());
            }
            System.out.println("Failed method: getNextDeviceID()");
            return -2;//error, can't find the settings line
        }

        public void setEditableDevice(int deviceID){
            try {
                String curDir = System.getProperty("user.dir");
                File settingsFile = new File(curDir, "Settings");


                BufferedReader reader = new BufferedReader(new FileReader(settingsFile.getPath()));
                List<String> csvFile = new ArrayList<>();

                //copy and edit new settings file
                String line;
                String[] lineArray;

                while ((line = reader.readLine()) != null) {
                    lineArray = line.split("=");
                    if (lineArray[0].equals("EditableDeviceID"))
                        csvFile.add(lineArray[0] + "=" + deviceID + "\n");
                    else
                        csvFile.add(lineArray[0] + "=" + lineArray[1] + "\n");
                }

                reader.close();

                //wipe settings
                settingsFile.delete();
                settingsFile.createNewFile();

                BufferedWriter writer = new BufferedWriter(new FileWriter(settingsFile.getPath()));
                //rewrite it
                for(int i = 0; i < csvFile.size(); i++)
                    writer.write(csvFile.get(i));

                writer.close();
            }catch (Exception ex)
            {
                System.out.println("setEditableDevice(): " + ex.toString());
            }
        }

        //creates a new dir (new version) and updates current database files
        public void updateDatabase(BufferedReader reader) {
            try {
                //get the dir the version dir's are going to be stored in
                String curDir = System.getProperty("user.dir");

                File DVFile = new File(curDir, "Database Versions");
                DVFile.createNewFile();//if it doesn't exit, create it

                //name and create the file
                DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
                LocalDateTime now = LocalDateTime.now();
                System.out.println(dtf.format(now) + " is the new file name");
                File newDir = new File(DVFile.getPath(), dtf.format(now));
                newDir.mkdir();//creates a new directory for today, or overwrites today's backup (if it exists)

                System.out.println("Creating csv files and writers...");

                //create files and writers
                File newFieldCsv = new File(newDir.getAbsolutePath(), "Field");
                File newRowsCsv = new File(newDir.getAbsolutePath(), "Row");
                File newContentsCsv = new File(newDir.getAbsolutePath(), "Content");

                newFieldCsv.delete();
                newRowsCsv.delete();
                newContentsCsv.delete();

                newFieldCsv.createNewFile();
                newRowsCsv.createNewFile();
                newContentsCsv.createNewFile();

                BufferedWriter fieldWr = new BufferedWriter(new FileWriter(newFieldCsv.getAbsolutePath()));
                BufferedWriter rowWr = new BufferedWriter(new FileWriter(newRowsCsv.getAbsolutePath()));
                BufferedWriter contentWr = new BufferedWriter(new FileWriter(newContentsCsv.getAbsolutePath()));

                //for each file
                System.out.println("Reading in and copying the incoming database files");
                String line;
                while (!(line = reader.readLine()).equals("break"))//break signals one of three files has been read, in order: fields, rows, contents.
                {
                    //load the incoming field file into a new field file inside our new dir
                    fieldWr.write(line + "\n");
                }

                while (!(line = reader.readLine()).equals("break")) {
                    //load the incoming row file into a new row file inside our new dir
                    rowWr.write(line + "\n");
                }

                while (!(line = reader.readLine()).equals("break")) {
                    //load the incoming content file into a new content file inside our new dir
                    contentWr.write(line + "\n");
                }

                //close file writers
                fieldWr.close();
                rowWr.close();
                contentWr.close();

                //tell the device that the overwrite was successful
                DataOutputStream writerBuff = new DataOutputStream(connection.getOutputStream());
                writerBuff.write(("Push Completed Successfully" + "\n").getBytes(), 0, new String("Push Completed Successfully" + "\n").length());

                System.out.println("UpdateDatabase method completed successfully");
                reader.close();
            } catch (Exception ex) {
                System.out.println("updateDatabase(): " + ex.toString());
            }
        }

        //If a table csv doesn't exist, it'll make one and send an empty file
        //sends latest database files
        public void sendLatestDatabase() {
            try {
                DataOutputStream writer = new DataOutputStream(connection.getOutputStream());

                System.out.println("Connected to device");
                String curDir = System.getProperty("user.dir");
                File dir = new File(curDir,  "Database Versions");

                dir.createNewFile();//if it doesn't exit, create it

                String curDate  = "0";
                if(dir.listFiles() == null)
                {
                    //if there are no files in the database, stop.
                    System.out.println("No database versions found");
                    writer.write(("Failed" + "\n").getBytes(), 0, new String("Failed" + "\n").length());

                    writer.close();
                    return;
                }
                //look through our files to find the latest one
                //compare each character from most significant to least, stopping when one is bigger than the other.
                for (int i = 0; i < dir.listFiles().length; i++) {//per file in dir
                    String fileDate = dir.listFiles()[i].getName();
                    for(int j = 0; j < fileDate.length(); j++) {//per character
                        if (curDate.charAt(j) < fileDate.charAt(j))//if the fileDate is bigger
                        {
                            curDate = fileDate;
                            break;
                        }
                    }
                }
                if(curDate.equals("0"))
                {
                    //if there are no files in the database, stop.
                    System.out.println("No database versions found");
                    writer.write(("Failed" + "\n").getBytes(), 0, new String("Failed" + "\n").length());

                    writer.close();
                    return;
                }
                else{
                    //return a blank line, to signify success
                    writer.write(("Success" + "\n").getBytes(), 0, new String("Success" + "\n").length());
                }
                System.out.println("Sent success message, now sending csv files");
                System.out.println("csvfile dir: " + curDate);

                //now we have the name of the latest dir
                //send the csv files

                //fields
                File curDateFile = new File(dir.getPath(), String.valueOf(curDate));
                curDateFile.createNewFile();

                File fieldFile = new File(curDateFile.getPath(), "Field");
                fieldFile.createNewFile();

                FileReader fr = new FileReader(fieldFile.getPath()); //Database Versions/curDate/Field
                BufferedReader reader = new BufferedReader(fr);

                String line;
                while((line = reader.readLine()) != null)
                    writer.write((line + "\n").getBytes(), 0, new String(line + "\n").length());
                writer.write(("break" + "\n").getBytes(), 0, new String("break" + "\n").length());

                reader.close();

                //rows
                File rowFile = new File(curDateFile, "Row");
                rowFile.createNewFile();

                fr = new FileReader(rowFile.getPath());
                reader = new BufferedReader(fr);

                while((line = reader.readLine()) != null)
                    writer.write((line + "\n").getBytes(), 0, new String(line + "\n").length());
                writer.write(("break" + "\n").getBytes(), 0, new String("break" + "\n").length());

                reader.close();

                //contents
                File contentFile = new File(curDateFile, "Content");
                contentFile.createNewFile();

                fr = new FileReader(contentFile.getPath());
                reader = new BufferedReader(fr);

                while((line = reader.readLine()) != null)
                    writer.write((line + "\n").getBytes(), 0, new String(line + "\n").length());
                writer.write(("break" + "\n").getBytes(), 0, new String("break" + "\n").length());

                reader.close();
                writer.close();

                System.out.println("Sent all files, request terminating.");
            } catch (Exception ex) {
                System.out.println(ex.toString());
            }
        }
    }
}