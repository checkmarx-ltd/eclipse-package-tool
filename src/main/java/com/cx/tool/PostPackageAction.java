package com.cx.tool;

import com.cx.tool.util.CommandLineProcess;
import com.cx.tool.util.FileUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
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

/**
 * Created by: iland
 * Date: 7/18/2019
 */
public class PostPackageAction {

    private static final int BUFFER_SIZE = 4096;
    private static final String ALIAS = "cd46aa5a-e3e8-11e2-bd77-cc52af8192da";
    private static final String KS_PASS = "neige";
    private static String KS_PATH;

    private static final int DEFAULT_BUFFER_SIZE = 1024 * 4;

    public static void main(String[] args) throws Exception {
        KS_PATH = new File(args[0]).getParent() + File.separator + "resources" + File.separator + "mymcre.pfx";

        FilenameFilter filter = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("CxEclipsePlugin");
            }
        };
        File srcFile = Objects.requireNonNull(new File(args[0]).listFiles(filter))[0];

        String tmpDirName = args[0] + "\\CxEclipsePlugin-Temp";
        File tempDir = new File(tmpDirName);
        String srcArtJar = args[0] + "\\CxEclipsePlugin-Temp\\artifacts.jar";
        String trgArtJar = args[0] + "\\CxEclipsePlugin-Temp\\artifacts-temp.jar";

        extract(srcFile, tempDir);
        genFixedArtifactJar(srcArtJar, trgArtJar);

        signJars(tempDir);

        Files.deleteIfExists(Paths.get(srcArtJar));
        new File(trgArtJar).renameTo(new File(srcArtJar));

        String pluginName = srcFile.getAbsolutePath();
        Files.deleteIfExists(srcFile.toPath());

        zipDir(tempDir.getAbsolutePath(), pluginName, true);

        FileUtil.deleteDirectory(tempDir);
    }

    private static void zipDir(String fileToZip, String zipFile, boolean excludeContainingFolder)
            throws IOException {
        ZipOutputStream zipOut = null;
        try {
            zipOut = new ZipOutputStream(new FileOutputStream(zipFile));
            File srcFile = new File(fileToZip);
            if (excludeContainingFolder && srcFile.isDirectory()) {
                for (String fileName : srcFile.list()) {
                    addToZip("", fileToZip + "/" + fileName, zipOut);
                }
            } else {
                addToZip("", fileToZip, zipOut);
            }
        } finally {
            zipOut.flush();
            zipOut.close();
        }
        System.out.println("Successfully created " + zipFile);
    }

    private static void addToZip(String path, String srcFile, ZipOutputStream zipOut)
            throws IOException {
        File file = new File(srcFile);
        String filePath = "".equals(path) ? file.getName() : path + "/" + file.getName();
        if (file.isDirectory()) {
            for (String fileName : file.list()) {
                addToZip(filePath, srcFile + "/" + fileName, zipOut);
            }
        } else {
            zipOut.putNextEntry(new ZipEntry(filePath));
            FileInputStream in = new FileInputStream(srcFile);
            try {
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    zipOut.write(buffer, 0, len);
                }
            } finally {
                in.close();
            }
        }
    }

    private static boolean signJars(File dirPath) {
        List<File> files = getAllFilesFromDir(dirPath);
        boolean success = false;

        for (File file : files) {
            try {
                if (file.getName().endsWith(".jar") && !file.getName().equals("artifacts.jar")) {
                    if (!isJarSigned(file)) {
                        success = signJar(file);
                    }
                }
            } catch (Exception e) {
                System.out.println("Fail to sign jar: " + file.getName());
                break;
            }
        }

        return success;
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
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zipfile))) {
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
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void extractFile(ZipInputStream in, File outdir, String name) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(new File(outdir, name)));
        try {
            int count = -1;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        } finally {
            out.flush();
            out.close();
        }
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

    private static boolean isJarSigned(File jarFile) throws IOException {
        CommandLineProcess cli = new CommandLineProcess(jarFile.getParent(), isSignedCommand(jarFile.getAbsolutePath()));
        List<String> commandLines = cli.executeProcess();
        for (String line : commandLines) {
            if (line.contains("jar verified")) {
                return true;
            }
        }

        return false;
    }

    private static boolean signJar(File jarFile) throws IOException {
        CommandLineProcess cli = new CommandLineProcess(jarFile.getParent(), signJarCommand(KS_PATH, jarFile.getAbsolutePath()), KS_PASS);
        List<String> commandLines = cli.executeProcess();
        for (String line : commandLines) {
            if (line.contains("jar signed")) {
                return true;
            }
        }

        return false;
    }

    private static String[] isSignedCommand(String jarFile) {
        return new String[]{"jarsigner", "-verify", jarFile};
    }

    private static String[] signJarCommand(String keyStore, String jarFile) {
        return new String[]{"jarsigner", "-keystore", keyStore, jarFile, ALIAS};
    }

    private static List<File> getAllFilesFromDir(File dir) {
        return getAllFilesFromDir(dir, new ArrayList<File>());
    }

    private static List<File> getAllFilesFromDir(File dir, List<File> files) {
        if (dir != null && dir.isDirectory()) {
            for (File file : dir.listFiles()) {
                if (file.isDirectory()) {
                    getAllFilesFromDir(file, files);
                } else {
                    files.add(file);
                }
            }
        }
        return files;
    }

}
