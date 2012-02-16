package hudson.scm;

import hudson.util.Secret;

import java.io.File;

import org.netbeans.lib.cvsclient.Client;
import org.netbeans.lib.cvsclient.admin.StandardAdminHandler;
import org.netbeans.lib.cvsclient.command.Builder;
import org.netbeans.lib.cvsclient.command.CommandAbortedException;
import org.netbeans.lib.cvsclient.command.CommandException;
import org.netbeans.lib.cvsclient.command.GlobalOptions;
import org.netbeans.lib.cvsclient.command.history.HistoryCommand;
import org.netbeans.lib.cvsclient.command.log.LogBuilder;
import org.netbeans.lib.cvsclient.command.log.LogCommand;
import org.netbeans.lib.cvsclient.command.log.LogInformation;
import org.netbeans.lib.cvsclient.command.log.LogInformation.Revision;
import org.netbeans.lib.cvsclient.command.log.LogInformation.SymName;
import org.netbeans.lib.cvsclient.command.tag.TagCommand;
import org.netbeans.lib.cvsclient.command.update.UpdateCommand;
import org.netbeans.lib.cvsclient.commandLine.BasicListener;
import org.netbeans.lib.cvsclient.connection.AuthenticationException;
import org.netbeans.lib.cvsclient.connection.PServerConnection;
import org.netbeans.lib.cvsclient.connection.StandardScrambler;
import org.netbeans.lib.cvsclient.event.FileInfoEvent;
import org.netbeans.lib.cvsclient.event.MessageEvent;

public class CvsListTagsTest {

    //TODO: read repos and modules from config
    //TODO: show tags of a folder (how exactly is that defined?)
    
    //TODO: tags cachen?
    //TODO: Unterscheiden zwischen Tags und Branch
    
    
    /**
     * @param args
     */
    public static void main(String[] args) {
        String userName = "cvsuser";
        String encodedPassword = StandardScrambler.getInstance().scramble("cvs"); 
        String hostName = "localhost";
        String repository = "/var/lib/cvs";
        
        PServerConnection c = new PServerConnection();
        c.setUserName(userName);
        c.setEncodedPassword(encodedPassword);
        c.setHostName(hostName);
        c.setRepository(repository);
        try {
            c.open();
            Client client = new Client(c, new StandardAdminHandler());
            client.setLocalPath(new File("./checkout").getAbsolutePath());
            client.getEventManager().addCVSListener(new LogListener());
            
            LogCommand lc = new LogCommand();
            
            LogBuilder builder = new LogBuilder(client.getEventManager(), lc);
            lc.setBuilder(builder);
            client.executeCommand(lc, new GlobalOptions());

            
        } catch (CommandAbortedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (AuthenticationException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (CommandException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }
    
    public static class LogListener extends BasicListener{
        
        private LogInformation infoContainer;

        @Override
        public void messageSent(MessageEvent e) {
            /*
             * Override the super class, so as to prevent from logging to console.
             */
        }
         
        @Override
        public void fileInfoGenerated(FileInfoEvent fileinfoevent) {
            //Handle control to Super class.
            super.fileInfoGenerated(fileinfoevent);
             
            //Get the log information for the current file event.
            infoContainer = (LogInformation) fileinfoevent.getInfoContainer();
            for(SymName sn : infoContainer.getAllSymbolicNames()){
                System.out.println(infoContainer.getRepositoryFilename() + " " + sn.getName());
            }
        }
        
        public LogInformation getLogInfo(){
            return infoContainer;
        }
    }

}
