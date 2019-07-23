package com.cx.tool;

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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Created by: iland
 * Date: 7/18/2019
 */
public class PostPackageAction {

    private static final int BUFFER_SIZE = 4096;

    public static void main(String[] args) throws Exception {
        FilenameFilter filter = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("CxEclipsePlugin");
            }
        };
        File srcFile = Objects.requireNonNull(new File(args[0]).listFiles(filter))[0];

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

        zipPlugin(srcFilePath.toString(), trgZip, srcArtJar);

        FileUtil.deleteDirectory(tempDir);
        Files.deleteIfExists(srcFilePath);
    }

    private static void zipPlugin(String orgZip, String trgZip, String trgArtJar) throws Exception {
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
