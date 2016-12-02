import java.lang.System;
import java.net.Socket;
import java.net.UnknownHostException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.DataOutputStream;

//
// This is an implementation of a simplified version of a command 
// line ftp client. The program takes no arguments.
//

public class CSftp {
  static final int MAX_LEN = 255;

  // Application Command
  private final static String OPEN = "open";
  private final static String USER = "user";
  private final static String CLOSE = "close";
  private final static String QUIT = "quit";
  private final static String GET = "get";
  private final static String CD = "cd";
  private final static String DIR = "dir";
  
  // check if the connection is logged in with a user.
  private boolean loggedIn = false;

  private final static String INCORRECT = "901 Incorrect number of arguments.";

  private Socket socket;

  private BufferedReader reader;

  private PrintWriter writer;

  public static void main(String[] args) {

    CSftp clientFTP = new CSftp();
    clientFTP.initialize();

  }

  // Start the program
  public void initialize() {

    try {
      for (int len = 1; len > 0;) {
        System.out.print("csftp> ");
        byte cmdString[] = new byte[MAX_LEN];
        len = System.in.read(cmdString);
        // Change the byte into String.
        String str = new String(cmdString, "UTF-8");
        // Split string into argument
        String[] input = str.trim().split("\\s+");
        String cmd = input[0];

        if (cmd.equals(OPEN)) {
          open(input);
        } else if (cmd.equals(USER) && !loggedIn) {
          user(input);
        } else if (cmd.equals(QUIT)) {
          quit();
        } else if (socket != null && loggedIn && !cmd.equals(USER)) {
          switch (cmd) {
          case GET:
            get(input);
            break;
          case CD:
            cd(input);
            break;
          case DIR:
            dir();
            break;
          default:
            System.out.println("900 Invalid command.");
            break;
          }
        } else {
          System.out.println("999 Processing error. Unexpected command; try opening connection, then logging in.");
        }
        

      }
    } catch (IOException exception) {
      System.out.println("998 Input error while reading commands, terminating.");
    }
  }

  /**
   * Implementation of OPEN command.
   */
  public void open(String[] input) {
    int port = 21;
    if (input.length < 2) {
      System.out.println(INCORRECT + "\n" + OPEN + " requires arguments SERVER and (optional) PORT");
      return;
    }

    String server = input[1];
    if (input.length == 3) {
      port = Integer.parseInt(input[2]);
    }
    if (socket != null) {
      System.out.println("Socket already in use.");
      return;
    }

    System.out.println("-->" + OPEN + " server: " + server + " port: " + port);

    try {
      socket = new Socket(server, port);

      reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

      OutputStream outputStream = socket.getOutputStream();
      writer = new PrintWriter(outputStream);

      readServerOutput();
    } catch (UnknownHostException e) {
      System.out.println("920 Control connection to " + server + " on port " + Integer.toString(port)
          + " failed to open");
    } catch (IOException e) {
      System.out.println("925 Control connection I/O error, closing control connection.");
    }
  }

  /**
   * Implementation of USER command.
   */
  public void user(String[] input) {
    if (input.length != 2) {
      System.out.println(INCORRECT + "\n" + "Requires argument userName");
      return;
    }
    
    if (socket == null) {
      System.out.println("999 Processing error. Unexpected command; try opening connection, then logging in.");
      return;
    }
    
    String userName = input[1];

    sendServerInput("user " + userName);

    String output = readServerOutput();
    String[] outputResult = output.trim().split("\\s+");
    // Integer.parseInt(outputResult[0])
    if (Integer.parseInt(outputResult[0]) == 331) {
      try {
        System.out.print("password: ");
        byte cmdString[] = new byte[MAX_LEN];
        System.in.read(cmdString);
        // Change the byte into String.
        String pass = new String(cmdString, "UTF-8").trim();
        sendServerInput("pass " + pass);
        readServerOutput();
        loggedIn = true;
      } catch (IOException e) {
        System.out.println("925 Control connection I/O error, closing control connection.");
        e.printStackTrace();
      }
    }
  }

  /**
   * Implementation of QUIT command.
   */
  public void quit() {
    try {
      if (socket != null) {
        sendServerInput(QUIT);
        readServerOutput();
        socket.close();
        loggedIn = false;
      }
    } catch (IOException e) {
      System.out.println("925 Control connection I/O error, closing control connection.");
    }
    System.exit(0);
  }

  /**
   * Implementation of GET command.
   */
  public void get(String[] input) {
    if (input.length != 3) {
      System.out.println(INCORRECT + "\n" + GET + " requires arguments REMOTE and LOCAL");
      return;
    }

    String remote = input[1];
    String local = input[2];
    System.out.println(GET + " remote: " + remote + " local: " + local);

    String cmd = "RETR " + remote;

    sendServerInput("PASV");
    String output = readServerOutput();

    // check if user is logged in. if not logged in do not continue.
    if (output.length() < 3 || Integer.parseInt(output.substring(0, 3)) == 530) {
      return;
    }

    int start = output.indexOf('(');
    int end = output.indexOf(')');
    String port_Host = output.substring(start + 1, end);
    String[] portList = port_Host.trim().split(",");
    String ip = portList[0] + "." + portList[1] + "." + portList[2] + "." + portList[3];
    int port = Integer.parseInt(portList[4]) * 256 + Integer.parseInt(portList[5]);

    try {
      Socket dataSocket = new Socket(ip, port);
      BufferedInputStream bInput = new BufferedInputStream(dataSocket.getInputStream());

      sendServerInput(cmd);
      String response = readServerOutput();

      if (Integer.parseInt(response.substring(0, 3)) != 150) {
        throw new FileNotFoundException("");
      }

      ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
      BufferedOutputStream bOutput = new BufferedOutputStream(byteArray);

      byte[] buffer = new byte[4096];
      int byteRead = 0;
      while ((byteRead = bInput.read(buffer)) != -1) {
        bOutput.write(buffer, 0, byteRead);
      }
      bOutput.flush();
      bOutput.close();
      bInput.close();

      response = readServerOutput();

      File tempFile = new File(local);
      FileOutputStream fos = new FileOutputStream(tempFile);
      fos.write(byteArray.toByteArray());
      fos.flush();
      fos.close();

    } catch (FileNotFoundException e1) {
      System.out.println("910 Access to local file " + local + " denied.");
    } catch (UnknownHostException e) {
      System.out.println("930 Data transfer connection to " + ip + " on port " + Integer.toString(port)
          + " failed to open.");
    } catch (IOException e) {
      System.out.println("935 Data Transfer connection I/O error, closing control connection.");
    }
  }

  /**
   * Implementation of CD command.
   */
  public void cd(String[] input) {
    if (input.length != 2) {
      System.out.println(INCORRECT + "\n" + CD + " require argument DIRECTORY");
      return;
    }
    String directory = input[1];
    System.out.println(CD + " directory: " + directory);

    String cmd = "CWD " + directory;
    sendServerInput(cmd);
    String response = readServerOutput();

    if (Integer.parseInt(response.substring(0, 3)) != 250) {
      System.out.println("902 Invalid argument\n " + response);
    }

  }

  /**
   * Implementation of DIR command.
   */
  public void dir() {
    sendServerInput("PASV");
    String output = readServerOutput();

    // check if user is logged in. If not logged in do not continue.
    if (output.length() < 3 || Integer.parseInt(output.substring(0, 3)) == 530) {
      return;
    }

    int start = output.indexOf('(');
    int end = output.indexOf(')');
    String port_Host = output.substring(start + 1, end);
    String[] portList = port_Host.trim().split(",");
    String ip = portList[0] + "." + portList[1] + "." + portList[2] + "." + portList[3];
    int port = Integer.parseInt(portList[4]) * 256 + Integer.parseInt(portList[5]);

    sendServerInput("LIST");

    try {
      Socket dataSocket = new Socket(ip, port);

      readServerOutput();

      BufferedReader dataReader = new BufferedReader(new InputStreamReader(dataSocket.getInputStream()));

      do {
        String dataOutput = dataReader.readLine();
        System.out.println("<" + dataOutput);
      } while (dataReader.ready());

      readServerOutput();

      dataSocket.close();

    } catch (UnknownHostException e) {
      System.out.println("930 Data transfer connection to " + ip + " on port " + Integer.toString(port)
          + " failed to open.");
    } catch (IOException e) {
      System.out.println("935 Data Transfer connection I/O error, closing control connection.");
    }
  }

  /**
   * Common helper to use OutputStream to send any command.
   */
  private void sendServerInput(String cmd) {
    if (socket != null) {
      cmd = cmd + "\r\n";

      writer.print(cmd);
      writer.flush();

      System.out.println("-->" + cmd);
    }
  }


  private String readServerOutput() {
    String line = "";
    try {
      do {
        line = reader.readLine();
        System.out.println("<--" + line);
      } while (reader.ready());
      if (line == null) {
        line = "";
      }
    } catch (IOException e) {
      System.out.println("935 Data transfer connection I/O error, closing data connection.");
    }
    return line;
  }

}