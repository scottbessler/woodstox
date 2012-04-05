package wstxtest.sax;

import java.io.*;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import com.ctc.wstx.sax.WstxSAXParserFactory;

import wstxtest.BaseWstxTest;

/**
 * Simple unit tests to verify that most fundamental parsing functionality
 * works via Woodstox SAX implementation.
 */
public class TestEntityResolver
    extends BaseWstxTest
{
    public void testWithDummyExtSubset()
        throws Exception
    {
        final String XML =
            "<!DOCTYPE root PUBLIC '//some//public//id' 'no-such-thing.dtd'>\n"
            +"<root />"
            ;

        SAXParserFactory spf = new WstxSAXParserFactory();
        spf.setNamespaceAware(true);
        SAXParser sp = spf.newSAXParser();
        DefaultHandler h = new DefaultHandler();

        /* First: let's verify that we get an exception for
         * unresolved reference...
         */
        try {
            sp.parse(new InputSource(new StringReader(XML)), h);
        } catch (SAXException e) {
            verifyException(e, "No such file or directory");
        }

        // And then with dummy resolver; should work ok now
        sp = spf.newSAXParser();
        sp.getXMLReader().setEntityResolver(new MyResolver("   "));
        h = new DefaultHandler();
        try {
            sp.parse(new InputSource(new StringReader(XML)), h);
        } catch (SAXException e) {
            fail("Should not have failed with entity resolver, got ("+e.getClass()+"): "+e.getMessage());
        }
    }

    /*
    ///////////////////////////////////////////////////////
    // Helper classes
    ///////////////////////////////////////////////////////
     */

    static class MyResolver
        implements EntityResolver
    {
        final String mContents;

        public MyResolver(String c) {
            mContents = c;
        }

        public InputSource resolveEntity(String publicId, String systemId)
        {
            return new InputSource(new StringReader(mContents));
        }
    }
}
