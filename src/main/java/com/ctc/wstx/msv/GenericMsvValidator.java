/* Woodstox XML processor
 *
 * Copyright (c) 2004- Tatu Saloranta, tatu.saloranta@iki.fi
 *
 * Licensed under the License specified in the file LICENSE which is
 * included with the source code.
 * You may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ctc.wstx.msv;

import java.util.*;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;

import org.codehaus.stax2.validation.*;

import org.relaxng.datatype.Datatype;

import com.sun.msv.grammar.IDContextProvider2;
import com.sun.msv.util.DatatypeRef;
import com.sun.msv.util.StartTagInfo;
import com.sun.msv.util.StringRef;
import com.sun.msv.verifier.Acceptor;
import com.sun.msv.verifier.DocumentDeclaration;
import com.sun.msv.verifier.regexp.StringToken;

import com.ctc.wstx.util.ElementId;
import com.ctc.wstx.util.ElementIdMap;
import com.ctc.wstx.util.PrefixedName;
import com.ctc.wstx.util.TextAccumulator;

/**
 * Generic validator instance to be used for all Multi-Schema Validator
 * backed implementations. A common class can be used since functionality
 * is almost identical between variants (RNG, W3C SChema); minor
 * differences that exist can be configured by settings provided.
 *<p>
 * Note about id context provider interface: while it'd be nice to
 * separate that part out, it is unfortunately closely tied to the
 * validation process. Hence it's directly implemented by this class.
 */
public final class GenericMsvValidator
    extends XMLValidator
    implements com.sun.msv.grammar.IDContextProvider2
{
    /*
    ///////////////////////////////////////////////////////////////////////
    // Configuration
    ///////////////////////////////////////////////////////////////////////
    */

    final protected XMLValidationSchema mParentSchema;

    final protected ValidationContext mContext;

    final protected DocumentDeclaration mVGM;

    /*
    ///////////////////////////////////////////////////////////////////////
    // State, helper objects
    ///////////////////////////////////////////////////////////////////////
    */

    final protected ArrayList mAcceptors = new ArrayList();

    protected Acceptor mCurrAcceptor = null;

    final protected TextAccumulator mTextAccumulator = new TextAccumulator();

    /**
     * Map that contains information about element id (values of attributes
     * or textual content with type ID) declarations and references
     */
    protected ElementIdMap mIdDefs;

    /*
    ///////////////////////////////////////////////////////////////////////
    // State, positions
    ///////////////////////////////////////////////////////////////////////
    */

    protected String mCurrAttrPrefix;

    protected String mCurrAttrLocalName;

    /**
     * Sometimes a problem object has to be temporarily
     * stored, and only reported later on. This happens
     * when exceptions can not be thrown via code outside
     * of Woodstox (like validation methods in MSV that do
     * callbacks).
     */
    protected XMLValidationProblem mProblem;

    /*
    ///////////////////////////////////////////////////////////////////////
    // Helper objects
    ///////////////////////////////////////////////////////////////////////
    */

    final StringRef mErrorRef = new StringRef();

    /**
     * StartTagInfo instance need not be thread-safe, and it is not immutable
     * so let's reuse one instance during a single validation.
     */
    final StartTagInfo mStartTag = new StartTagInfo("", "", "", null, (IDContextProvider2) null);

    /**
     * This object provides limited access to attribute values of the
     * currently validated element.
     */
    final AttributeProxy mAttributeProxy;

    /*
    ///////////////////////////////////////////////////////////////////////
    // Construction, configuration
    ///////////////////////////////////////////////////////////////////////
    */

    public GenericMsvValidator(XMLValidationSchema parent, ValidationContext ctxt,
                               DocumentDeclaration vgm)
    {
        mParentSchema = parent;
        mContext = ctxt;
        mVGM = vgm;

        mCurrAcceptor = mVGM.createAcceptor();
        mAttributeProxy = new AttributeProxy(ctxt);
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // IDContextProvider2 implementation:
    //
    // Core RelaxNG ValidationContext implementation
    // (org.relaxng.datatype.ValidationContext, base interface
    // of the id provider context)
    ///////////////////////////////////////////////////////////////////////
     */

    public String getBaseUri()
    {
        return mContext.getBaseUri();
    }

    public boolean isNotation(String notationName)
    {
        return mContext.isNotationDeclared(notationName);
    }

    public boolean isUnparsedEntity(String entityName)
    {
        return mContext.isUnparsedEntityDeclared(entityName);
    }

    public String resolveNamespacePrefix(String prefix) 
    {
        return mContext.getNamespaceURI(prefix);
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // IDContextProvider2 implementation, extensions over
    // core ValidationContext
    ///////////////////////////////////////////////////////////////////////
     */

    /**
     *<p>
     * Note: we have to throw a dummy marker exception, which merely
     * signals that a validation problem is to be reported.
     * This is obviously messy, but has to do for now.
     */
    public void onID(Datatype datatype, StringToken idToken)
        throws IllegalArgumentException
    {
        if (mIdDefs == null) {
            mIdDefs = new ElementIdMap();
        }

        int idType = datatype.getIdType();
        Location loc = mContext.getValidationLocation();
        PrefixedName elemPName = getElementPName();
        PrefixedName attrPName = getAttrPName();

        if (idType == Datatype.ID_TYPE_ID) {
            String idStr = idToken.literal.trim();
            ElementId eid = mIdDefs.addDefined(idStr, loc, elemPName, attrPName);
            // We can detect dups by checking if Location is the one we passed:
            if (eid.getLocation() != loc) {
                mProblem = new XMLValidationProblem(loc, "Duplicate id '"+idStr+"', first declared at "+eid.getLocation());
                mProblem.setReporter(this);
            }
        } else if (idType == Datatype.ID_TYPE_IDREF) {
            String idStr = idToken.literal.trim();
            mIdDefs.addReferenced(idStr, loc, elemPName, attrPName);
        } else if (idType == Datatype.ID_TYPE_IDREFS) {
            StringTokenizer tokens = new StringTokenizer(idToken.literal);
            while (tokens.hasMoreTokens()) {
                mIdDefs.addReferenced(tokens.nextToken(), loc, elemPName, attrPName);
            }
        } else { // sanity check
            throw new IllegalStateException("Internal error: unexpected ID datatype: "+datatype);
        }
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // XMLValidator implementation
    ///////////////////////////////////////////////////////////////////////
    */

    public XMLValidationSchema getSchema() {
        return mParentSchema;
    }

    /**
     * Method called to update information about the newly encountered (start)
     * element. At this point namespace information has been resolved, but
     * no DTD validation has been done. Validator is to do these validations,
     * including checking for attribute value (and existence) compatibility.
     */
    public void validateElementStart(String localName, String uri, String prefix)
        throws XMLStreamException
    {

        /* [WSTX-200]: If sub-tree we were to validate has ended, we
         *   have no current acceptor, and must quite. Ideally we would
         *   really handle this more cleanly but...
         */
        if (mCurrAcceptor == null) {
            return;
        }

        // Very first thing: do we have text collected?
        if (mTextAccumulator.hasText()) {
            doValidateText(mTextAccumulator);
        }

        /* 31-Mar-2006, TSa: MSV seems to require empty String for empty/no
         *   namespace, not null.
         */
        if (uri == null) {
            uri = "";
        }

        /* Do we need to properly fill it? Or could we just put local name?
         * Looking at code, I do believe it's only used for error reporting
         * purposes...
         */
        //String qname = (prefix == null || prefix.length() == 0) ? localName : (prefix + ":" +localName);
        String qname = localName;
        mStartTag.reinit(uri, localName, qname, mAttributeProxy, this);

        mCurrAcceptor = mCurrAcceptor.createChildAcceptor(mStartTag, mErrorRef);
        /* As per documentation, the side-effect of getting the error message
         * is that we also get a recoverable non-null acceptor... thus, should
         * never (?) see null acceptor being returned
         */
        if (mErrorRef.str != null) {
            reportError(mErrorRef);
        }
        if (mProblem != null) { // pending problems (to throw exception on)?
            XMLValidationProblem p = mProblem;
            mProblem = null;
            mContext.reportProblem(p);
        }
        mAcceptors.add(mCurrAcceptor);
    }

    public String validateAttribute(String localName, String uri,
                                    String prefix, String value)
        throws XMLStreamException
    {
        mCurrAttrLocalName = localName;
        mCurrAttrPrefix = prefix;
        if (mCurrAcceptor != null) {

            String qname = localName; // for now, let's assume we don't need prefixed version
            DatatypeRef typeRef = null; // for now, let's not care

            /* 31-Mar-2006, TSa: MSV seems to require empty String for empty/no
             *   namespace, not null.
             */
            if (uri == null) {
                uri = "";
            }

            if (!mCurrAcceptor.onAttribute2(uri, localName, qname, value, this, mErrorRef, typeRef)
                || mErrorRef.str != null) {
                reportError(mErrorRef);
            }
            if (mProblem != null) { // pending problems (to throw exception on)?
                XMLValidationProblem p = mProblem;
                mProblem = null;
                mContext.reportProblem(p);
            }
        }
        /* No normalization done by RelaxNG, is there? (at least nothing
         * visible to callers that is)
         */
        return null;
    }

    public String validateAttribute(String localName, String uri,
                                    String prefix,
                                    char[] valueChars, int valueStart,
                                    int valueEnd)
        throws XMLStreamException
    {
        int len = valueEnd - valueStart;
        /* This is very sub-optimal... but MSV doesn't deal with char
         * arrays.
         */
        return validateAttribute(localName, uri, prefix,
                                 new String(valueChars, valueStart, len));
    }
    
    public int validateElementAndAttributes()
        throws XMLStreamException
    {
        // Not handling any attributes
        mCurrAttrLocalName = mCurrAttrPrefix = "";
        if (mCurrAcceptor != null) {
            /* start tag info is still intact here (only attributes sent
             * since child acceptor was created)
             */
            if (!mCurrAcceptor.onEndAttributes(mStartTag, mErrorRef)
                || mErrorRef.str != null) {
                reportError(mErrorRef);
            }

            int stringChecks = mCurrAcceptor.getStringCareLevel();
            switch (stringChecks) {
            case Acceptor.STRING_PROHIBITED: // only WS
                return XMLValidator.CONTENT_ALLOW_WS;
            case Acceptor.STRING_IGNORE: // anything (mixed content models)
                return XMLValidator.CONTENT_ALLOW_ANY_TEXT;
            case Acceptor.STRING_STRICT: // validatable (data-oriented)
                return XMLValidator.CONTENT_ALLOW_VALIDATABLE_TEXT;
            default:
                throw new IllegalArgumentException("Internal error: unexpected string care level value return by MSV: "+stringChecks);
            }
        }

        // If no acceptor, we are recovering, no need or use to validate text
        return CONTENT_ALLOW_ANY_TEXT;
    }

    /**
     * @return Validation state that should be effective for the parent
     *   element state
     */
    public int validateElementEnd(String localName, String uri, String prefix)
        throws XMLStreamException
    {
        // Very first thing: do we have text collected?
        /* 27-Feb-2009, TSa: [WSTX-191]: Actually MSV expects us to call
         *   validation anyway, in case there might be restriction(s) on
         *   textual content. Otherwise we'll get an error.
         */
        doValidateText(mTextAccumulator);

        /* [WSTX-200]: need to avoid problems when doing sub-tree
         *   validation... not a proper solution, but has to do for
         *   now
         */
        int lastIx = mAcceptors.size()-1;
        if (lastIx < 0) {
            return XMLValidator.CONTENT_ALLOW_WS;
        }

        Acceptor acc = (Acceptor)mAcceptors.remove(lastIx);
        if (acc != null) { // may be null during error recovery? or not?
            if (!acc.isAcceptState(mErrorRef) || mErrorRef.str != null) {
                reportError(mErrorRef);
            }
        }
        if (lastIx == 0) { // root closed
            mCurrAcceptor = null;
        } else {
            mCurrAcceptor = (Acceptor) mAcceptors.get(lastIx-1);
        }
        if (mCurrAcceptor != null && acc != null) {
            if (!mCurrAcceptor.stepForward(acc, mErrorRef)
                || mErrorRef.str != null) {
                reportError(mErrorRef);
            }
            int stringChecks = mCurrAcceptor.getStringCareLevel();
            switch (stringChecks) {
            case Acceptor.STRING_PROHIBITED: // only WS
                return XMLValidator.CONTENT_ALLOW_WS;
            case Acceptor.STRING_IGNORE: // anything (mixed content models)
                return XMLValidator.CONTENT_ALLOW_ANY_TEXT;
            case Acceptor.STRING_STRICT: // validatable (data-oriented)
                return XMLValidator.CONTENT_ALLOW_VALIDATABLE_TEXT;
            default:
                throw new IllegalArgumentException("Internal error: unexpected string care level value return by MSV: "+stringChecks);
            }
        }
        return XMLValidator.CONTENT_ALLOW_ANY_TEXT;
    }

    public void validateText(String text, boolean lastTextSegment)
        throws XMLStreamException
    {
        /* If we got here, then it's likely we do need to call onText2().
         * (not guaranteed, though; in case of multiple parallel validators,
         * only one of them may actually be interested)
         */
        mTextAccumulator.addText(text);
        if (lastTextSegment) {
            doValidateText(mTextAccumulator);
        }
    }

    public void validateText(char[] cbuf, int textStart, int textEnd,
                             boolean lastTextSegment)
        throws XMLStreamException
    {
        /* If we got here, then it's likely we do need to call onText().
         * (not guaranteed, though; in case of multiple parallel validators,
         * only one of them may actually be interested)
         */
        mTextAccumulator.addText(cbuf, textStart, textEnd);
        if (lastTextSegment) {
            doValidateText(mTextAccumulator);
        }
    }

    public void validationCompleted(boolean eod)
        throws XMLStreamException
    {
        /* Ok, so, we should verify that there are no undefined
         * IDREF/IDREFS references. But only if we hit EOF, not
         * if validation was cancelled.
         */
        if (eod) {
            if (mIdDefs != null) {
                ElementId ref = mIdDefs.getFirstUndefined();
                if (ref != null) { // problem!
                    String msg = "Undefined ID '"+ref.getId()
                        +"': referenced from element <"
                        +ref.getElemName()+">, attribute '"
                        +ref.getAttrName()+"'";
                    reportError(msg, ref.getLocation());
                }
            }
        }
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // Attribute info access
    ///////////////////////////////////////////////////////////////////////
    */

    // // // Access to type info

    public String getAttributeType(int index)
    {
        // !!! TBI
        return null;
    }    

    public int getIdAttrIndex()
    {
        // !!! TBI
        return -1;
    }

    public int getNotationAttrIndex()
    {
        // !!! TBI
        return -1;
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // Internal methods
    ///////////////////////////////////////////////////////////////////////
    */

    PrefixedName getElementPName()
    {
        return PrefixedName.valueOf(mContext.getCurrentElementName());
    }
    
    PrefixedName getAttrPName()
    {
        return new PrefixedName(mCurrAttrPrefix, mCurrAttrLocalName);
    }

    void doValidateText(TextAccumulator textAcc)
        throws XMLStreamException
    {
        if (mCurrAcceptor != null) {
            String str = textAcc.getAndClear();
            DatatypeRef typeRef = null;
            if (!mCurrAcceptor.onText2(str, this, mErrorRef, typeRef)
                || mErrorRef.str != null) {
                reportError(mErrorRef);
            }
        }
    }

    private void reportError(StringRef errorRef)
        throws XMLStreamException
    {
        String msg = errorRef.str;
        errorRef.str = null;
        if (msg == null) {
            msg = "Unknown reason";
        }
        reportError(msg);
    }

    private void reportError(String msg)
        throws XMLStreamException
    {
        reportError(msg, mContext.getValidationLocation());
    }

    private void reportError(String msg, Location loc)
        throws XMLStreamException
    {
        XMLValidationProblem prob = new XMLValidationProblem(loc, msg, XMLValidationProblem.SEVERITY_ERROR);
        prob.setReporter(this);
        mContext.reportProblem(prob);
    }
}
