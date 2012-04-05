package stax2.typed;

import javax.xml.stream.*;

import org.codehaus.stax2.XMLStreamReader2;

/**
 * Stax2 Typed Access API basic reader tests, using native Stax2
 * typed reader implementation.
 *<p>
 * Note: currently some functionality is only supported with native
 * readers
 */
public class TestNativeReader
    extends ReaderTestBase
{
    protected XMLStreamReader2 getReader(String contents)
        throws XMLStreamException
    {
        XMLInputFactory f = getInputFactory();
        setCoalescing(f, false); // shouldn't really matter
        setNamespaceAware(f, true);
        return (XMLStreamReader2) constructStreamReader(f, contents);
    }
}

