package com.cx.tool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Created by: iland
 * Date: 7/18/2019
 */
public class PostPackageAction {

    private static final int BUFFER_SIZE = 4096;
    static List<File> fileList = new ArrayList<File>();  

    public static void main(String[] args) throws Exception {
        try {
			FilenameFilter filter = new FilenameFilter() {
			    public boolean accept(File dir, String name) {
			        return name.startsWith("CxEclipsePlugin");
			    }
			};
			File srcFile = Objects.requireNonNull(new File(args[0]).listFiles(filter))[0];
			File resourceLocation = new File(args[1]);

			File tempDir = new File(args[0] + "\\CxEclipsePlugin-Temp");
			String srcArtJar = args[0] + "\\CxEclipsePlugin-Temp\\artifacts.jar";
			String trgArtJar = args[0] + "\\CxEclipsePlugin-Temp\\artifacts-temp.jar";

			extract(srcFile, tempDir);
			genFixedArtifactJar(srcArtJar, trgArtJar);

			Files.deleteIfExists(Paths.get(srcArtJar));
			new File(trgArtJar).renameTo(new File(srcArtJar));

			String pluginName = srcFile.getName();

			Path srcFilePath = Paths.get(srcFile.getCanonicalPath()
			        .substring(0, srcFile.getCanonicalPath().lastIndexOf(".zip")) + "-org.zip");
			srcFile.renameTo(srcFilePath.toFile());

			String trgZip = args[0] + "\\" + pluginName;
			copyFile(resourceLocation.getPath() ,tempDir.getPath());
			signPlugin(tempDir);
			deleteBatchKeystoreFiles(tempDir);
			zipPlugin(tempDir, trgZip, srcArtJar);
			//TODO : Will uncomment it once all testings are done satisfactorily
//			FileUtil.deleteDirectory(tempDir);
			Files.deleteIfExists(srcFilePath);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			System.out.println(" IN FINALLY ....");
		}
    }

	private static void deleteBatchKeystoreFiles(File tempDir) {
		 try {
	            Files.deleteIfExists(
	                Paths.get(tempDir+"\\" + "jar_signer.bat"));
	            Files.deleteIfExists(
		                Paths.get(tempDir+"\\" + "mymcre.pfx"));
	        }
	        catch (NoSuchFileException e) {
	            System.out.println(
	                "No such file/directory exists");
	        } catch (IOException e) {
				e.printStackTrace();
			}		
	}

	public static void copyFile(String from, String to) throws IOException {
		Path srcBatch = Paths.get(from+ "\\" +"jar_signer.bat");
		Path destBatch = Paths.get(to+ "\\" +"jar_signer.bat");
		Path srcPfx = Paths.get(from+ "\\" +"mymcre.pfx");
		Path destPfx = Paths.get(to+ "\\" +"mymcre.pfx");
		
		Files.copy(srcBatch, destBatch, StandardCopyOption.COPY_ATTRIBUTES);
		Files.copy(srcPfx, destPfx, StandardCopyOption.COPY_ATTRIBUTES);
	}


    private static void signPlugin(File tempDir) {
    	ProcessBuilder processBuilder = new ProcessBuilder("cmd", "/c", "jar_signer.bat");
    	processBuilder.directory(tempDir);
    	try {
            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line + "\n");
            }
            int exitVal = process.waitFor();
            if (exitVal == 0) {
                System.out.println(output);
            } else {
            	System.out.println("error");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    
    private static void zipPlugin(File /*orgZip*/tempDir, String trgZip, String trgArtJar) throws Exception {/*
        ZipFile zipFile = new ZipFile(orgZip);
        final ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(trgZip));
        for (Enumeration e = zipFile.entries(); e.hasMoreElements(); ) {
            ZipEntry entryIn = (ZipEntry) e.nextElement();
            if (!entryIn.getName().equalsIgnoreCase("artifacts.jar")) {
                zos.putNextEntry(entryIn);
                InputStream is = zipFile.getInputStream(entryIn);
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) > 0) {
                    zos.write(buf, 0, len);
                }
                is.close();
            } else {
                zos.putNextEntry(new ZipEntry("artifacts.jar"));

                InputStream is = new FileInputStream(new File(trgArtJar));
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) > 0) {
                    zos.write(buf, 0, len);
                }
                is.close();
            }
            zos.closeEntry();
        }
        zos.close();
        zipFile.close();
    */
    	 // get list of files  
    	
        List<File> fileList = getFileList(tempDir);  
        //go through the list of files and zip them   
       zipFiles(fileList, trgZip, tempDir); 
    }

    private static void zipFiles(List<File> fileList, String trgZip, File tempDir)  
    {  
      try  
      {  
        // Creating ZipOutputStream - Using input name to create output name  
        FileOutputStream fos = new FileOutputStream(trgZip);  
        ZipOutputStream zos = new ZipOutputStream(fos);  
        // looping through all the files  
        for(File file : fileList)  
        {  
          // To handle empty directory  
          if(file.isDirectory())  
          {  
            // ZipEntry --- Here file name can be created using the source file  
            ZipEntry ze = new ZipEntry(getFileName(file.toString(), tempDir)+"/");  
            // Putting zipentry in zipoutputstream  
            zos.putNextEntry(ze);  
            zos.closeEntry();  
          }  
          else  
          {  
            FileInputStream fis = new FileInputStream(file);  
            BufferedInputStream bis = new BufferedInputStream(fis, BUFFER_SIZE);  
            // ZipEntry --- Here file name can be created using the source file  
            ZipEntry ze = new ZipEntry(getFileName(file.toString(), tempDir));  
            // Putting zipentry in zipoutputstream  
            zos.putNextEntry(ze);  
            byte data[] = new byte[BUFFER_SIZE];  
            int count;  
            while((count = bis.read(data, 0, BUFFER_SIZE)) != -1)   
            {  
                zos.write(data, 0, count);  
            }  
            bis.close();  
            zos.closeEntry();  
          }                 
        }                  
        zos.close();      
      }  
      catch(IOException ioExp)  
      {  
        System.out.println("Error while zipping " + ioExp.getMessage());  
        ioExp.printStackTrace();  
      }  
    }  
        
   //the method returns a list of files  
    private static List<File> getFileList(File source)  
    {   
      if(source.isFile())  
      {  
        fileList.add(source);  
      }  
      else if(source.isDirectory())  
      {  
        String[] subList = source.list();  
        // this condition checks for empty directory  
        if(subList.length == 0)  
        {  
          //System.out.println("path -- " + source.getAbsolutePath());  
          fileList.add(new File(source.getAbsolutePath()));  
        }  
        for(String child : subList)  
        {  
          getFileList(new File(source, child));  
        }  
      }  
      return fileList;  
    }  
        
    private static String getFileName(String filePath, File tempDir)  
    {  
      String name = filePath.substring(tempDir.getAbsolutePath().length() + 1, filePath.length());  
      return name;        
    }  
    
    private static void genFixedArtifactJar(String srcArtJar, String trgArtJar) throws Exception {
        JarFile artJar = new JarFile(srcArtJar);
        FileOutputStream fos = new FileOutputStream(trgArtJar);
        final JarOutputStream jos = new JarOutputStream(fos);

        for (Enumeration e = artJar.entries(); e.hasMoreElements(); ) {
            JarEntry jEntry = (JarEntry) e.nextElement();
            if (!jEntry.getName().equalsIgnoreCase("artifacts.xml")) {
                jos.putNextEntry(jEntry);
                InputStream is = artJar.getInputStream(jEntry);
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                while ((len = is.read(buf)) > 0) {
                    jos.write(buf, 0, len);
                }
                is.close();
            } else {
                jos.putNextEntry(new ZipEntry("artifacts.xml"));

                InputStream is = artJar.getInputStream(jEntry);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                try {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(is);
                    doc.setXmlStandalone(true);
                    XPath xPath = XPathFactory.newInstance().newXPath();
                    NodeList propertyNodes = (NodeList) xPath.evaluate("//property[@name='download.md5']", doc, XPathConstants.NODESET);
                    for (int i = 0; i < propertyNodes.getLength(); i++) {
                        Node node = propertyNodes.item(i);
                        Node size = node.getParentNode().getAttributes().getNamedItem("size");
                        int sizeInt = Integer.valueOf(size.getNodeValue());
                        size.setNodeValue(String.valueOf(sizeInt - 1));
                        Node nextSibling = node.getNextSibling();
                        node.getParentNode().removeChild(node);
                        nextSibling.getParentNode().removeChild(nextSibling.getPreviousSibling());
                    }
                    Source xmlSource = new DOMSource(doc);
                    Result outputTarget = new StreamResult(outputStream);
                    TransformerFactory.newInstance().newTransformer().transform(xmlSource, outputTarget);
                    InputStream docIs = new ByteArrayInputStream(outputStream.toByteArray());
                    byte[] buf = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = docIs.read(buf)) > 0) {
                        jos.write(buf, 0, len);
                    }
                    docIs.close();
                } finally {
                    outputStream.close();
                    is.close();
                }
            }
            jos.closeEntry();
        }
        jos.close();
        artJar.close();
    }

    public static void extract(File zipfile, File outdir) {
        try {
            ZipInputStream zin = new ZipInputStream(new FileInputStream(zipfile));
            ZipEntry entry;
            String name, dir;
            while ((entry = zin.getNextEntry()) != null) {
                name = entry.getName();
                if (entry.isDirectory()) {
                    mkdirs(outdir, name);
                    continue;
                }

                dir = dirpart(name);
                if (dir != null)
                    mkdirs(outdir, dir);

                extractFile(zin, outdir, name);
            }
            zin.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void extractFile(ZipInputStream in, File outdir, String name) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(new File(outdir, name)));
        int count = -1;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        out.close();
    }

    private static void mkdirs(File outdir, String path) {
        File d = new File(outdir, path);
        if (!d.exists())
            d.mkdirs();
    }

    private static String dirpart(String name) {
        int s = name.lastIndexOf(File.separatorChar);
        return s == -1 ? null : name.substring(0, s);
    }

}
