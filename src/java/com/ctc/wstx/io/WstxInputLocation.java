/* Woodstox XML processor
 *
 * Copyright (c) 2004 Tatu Saloranta, tatu.saloranta@iki.fi
 *
 * Licensed under the License specified in file LICENSE, included with
 * the source code.
 * You may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ctc.wstx.io;

import java.io.Serializable;
import javax.xml.stream.Location;

import org.codehaus.stax2.XMLStreamLocation2;

import com.ctc.wstx.util.StringUtil;

/**
 * Basic implementation of {@link Location}, used by Wstx readers.
 */
public class WstxInputLocation
    implements Serializable, XMLStreamLocation2
{
    private static final long serialVersionUID = 1L;

    private final static WstxInputLocation sEmptyLocation
        = new WstxInputLocation(null, "", "", -1, -1, -1);

    /**
     * Enclosing (parent) input location; location from which current
     * location is derived.
     */
    final protected WstxInputLocation mContext;

    final protected String mPublicId, mSystemId;
    
    final protected int mCharOffset;
    final protected int mCol, mRow;

    transient protected String mDesc = null;

    /**
     * @param ctxt Enclosing input location, if any
     */
    public WstxInputLocation(WstxInputLocation ctxt,
                             String pubId, String sysId,
                             int charOffset, int row, int col)
    {
        mContext = ctxt;
        mPublicId = pubId;
        mSystemId = sysId;
        /* Overflow? Can obviously only handle limited range of overflows,
         * but let's do that at least?
         */
        mCharOffset = (charOffset < 0) ? Integer.MAX_VALUE : charOffset;
        mCol = col;
        mRow = row;
    }

    public static WstxInputLocation getEmptyLocation() {
        return sEmptyLocation;
    }
    
    public int getCharacterOffset() { return mCharOffset; }
    public int getColumnNumber() { return mCol; }
    public int getLineNumber() { return mRow; }
    
    public String getPublicId() { return mPublicId; }
    public String getSystemId() { return mSystemId; }

    /*
    ////////////////////////////////////////////////////////
    // StAX 2 API:
    ////////////////////////////////////////////////////////
     */

    public XMLStreamLocation2 getContext() { return mContext; }

    /*
    ////////////////////////////////////////////////////////
    // Overridden standard methods
    ////////////////////////////////////////////////////////
     */
    
    public String toString()
    {
        if (mDesc == null) {
            StringBuffer sb;
            if (mContext != null) {
                sb = new StringBuffer(200);
            } else {
                sb = new StringBuffer(80);
            }
            appendDesc(sb);
            mDesc = sb.toString();
        }
        return mDesc;
    }
    
    public int hashCode() {
        return mCharOffset ^ mRow ^ mCol + (mCol << 3);
    }
    
    public boolean equals(Object o) {
        if (!(o instanceof Location)) {
            return false;
        }
        Location other = (Location) o;
        // char offset should be good enough, without row/col:
        if (other.getCharacterOffset() != getCharacterOffset()) {
            return false;
        }
        String otherPub = other.getPublicId();
        if (otherPub == null) {
            otherPub = "";
        }
        if (!otherPub.equals(mPublicId)) {
            return false;
        }
        String otherSys = other.getSystemId();
        if (otherSys == null) {
            otherSys = "";
        }
        return otherSys.equals(mSystemId);
    }

    /*
    ////////////////////////////////////////////////////////
    // Internal methods:
    ////////////////////////////////////////////////////////
     */

    private void appendDesc(StringBuffer sb)
    {
        String srcId;

        if (mSystemId != null) {
            sb.append("[row,col,system-id]: ");
            srcId = mSystemId;
        } else if (mPublicId != null) {
            sb.append("[row,col,public-id]: ");
            srcId = mPublicId;
        } else {
            sb.append("[row,col {unknown-source}]: ");
            srcId = null;
        }
        sb.append('[');
        sb.append(mRow);
        sb.append(',');
        sb.append(mCol);

        // Uncomment for testing, to see the char offset:
        //sb.append(" #").append(mCharOffset);
        //sb.append("{").append(System.identityHashCode(this)).append("}");

        if (srcId != null) {
            sb.append(',');
            sb.append('"');
            sb.append(srcId);
            sb.append('"');
        }
        sb.append(']');
        if (mContext != null) {
            StringUtil.appendLF(sb);
            sb.append(" from ");
            mContext.appendDesc(sb);
        }
    }
}
