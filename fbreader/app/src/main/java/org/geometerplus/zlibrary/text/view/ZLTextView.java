/*
 * Copyright (C) 2007-2015 FBReader.ORG Limited <contact@fbreader.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.zlibrary.text.view;

import android.graphics.Bitmap;

import com.jni.bitmap_operations.JniBitmapHolder;

import org.fbreader.util.Boolean3;
import org.geometerplus.fbreader.bookmodel.FBTextKind;
import org.geometerplus.fbreader.bookmodel.TOCTree;
import org.geometerplus.fbreader.fbreader.FBReaderApp;
import org.geometerplus.fbreader.fbreader.options.ColorProfile;
import org.geometerplus.fbreader.fbreader.options.ViewOptions.GujiLayoutStyleEnum;
import org.geometerplus.fbreader.fbreader.options.ViewOptions.GujiPunctuationEnum;
import org.geometerplus.zlibrary.core.filesystem.ZLFile;
import org.geometerplus.zlibrary.core.library.ZLibrary;
import org.geometerplus.zlibrary.core.util.RationalNumber;
import org.geometerplus.zlibrary.core.util.ZLColor;
import org.geometerplus.zlibrary.core.view.Hull;
import org.geometerplus.zlibrary.core.view.SelectionCursor;
import org.geometerplus.zlibrary.core.view.ZLPaintContext;
import org.geometerplus.zlibrary.core.view.ZLPaintContext.ColorAdjustingMode;
import org.geometerplus.zlibrary.core.view.ZLPaintContext.ScalingType;
import org.geometerplus.zlibrary.core.view.ZLPaintContext.Size;
import org.geometerplus.zlibrary.text.hyphenation.ZLTextHyphenationInfo;
import org.geometerplus.zlibrary.text.hyphenation.ZLTextHyphenator;
import org.geometerplus.zlibrary.text.model.ZLTextAlignmentType;
import org.geometerplus.zlibrary.text.model.ZLTextMark;
import org.geometerplus.zlibrary.text.model.ZLTextModel;
import org.geometerplus.zlibrary.text.model.ZLTextParagraph;
import org.geometerplus.zlibrary.text.view.style.ZLTextNGStyle;
import org.geometerplus.zlibrary.text.view.style.ZLTextNGStyleDescription;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public abstract class ZLTextView extends ZLTextViewBase {
	public interface ScrollingMode {
		int NO_OVERLAPPING = 0;
		int KEEP_LINES = 1;
		int SCROLL_LINES = 2;
		int SCROLL_PERCENTAGE = 3;
	};

	private ZLTextModel myModel;

	private interface SizeUnit {
		int PIXEL_UNIT = 0;
		int LINE_UNIT = 1;
	};

	private int myScrollingMode;
	private int myOverlappingValue;
	private GujiPunctuationEnum myIsShowPunctuation;

	private ZLTextPage myPreviousPage = new ZLTextPage();
	private ZLTextPage myCurrentPage = new ZLTextPage();
	private ZLTextPage myNextPage = new ZLTextPage();

	private final HashMap<ZLTextLineInfo,ZLTextLineInfo> myLineInfoCache = new HashMap<ZLTextLineInfo,ZLTextLineInfo>();

	private ZLTextRegion.Soul myOutlinedRegionSoul;
	private boolean myShowOutline = true;

	private final ZLTextSelection mySelection = new ZLTextSelection(this);
	private final Set<ZLTextHighlighting> myHighlightings =
		Collections.synchronizedSet(new TreeSet<ZLTextHighlighting>());

	private CursorManager myCursorManager;
	
	public ZLTextView(FBReaderApp application) {
		super(application);
	}

	public synchronized void setModel(ZLTextModel model) {
		myCursorManager = model != null ? new CursorManager(model, getExtensionManager()) : null;

		mySelection.clear();
		myHighlightings.clear();

		myModel = model;
		myCurrentPage.reset();
		myPreviousPage.reset();
		myNextPage.reset();
		if (myModel != null) {
			final int paragraphsNumber = myModel.getParagraphsNumber();
			if (paragraphsNumber > 0) {
				myCurrentPage.moveStartCursor(myCursorManager.get(0));
			}
		}
		Application.getViewWidget().reset();
	}

	public final ZLTextModel getModel() {
		return myModel;
	}

	public ZLTextWordCursor getStartCursor() {
		if (myCurrentPage.StartCursor.isNull()) {
			preparePaintInfo(myCurrentPage);
		}
		return myCurrentPage.StartCursor;
	}

	public ZLTextWordCursor getEndCursor() {
		if (myCurrentPage.EndCursor.isNull()) {
			preparePaintInfo(myCurrentPage);
		}
		return myCurrentPage.EndCursor;
	}

	private synchronized void gotoMark(ZLTextMark mark) {
		if (mark == null) {
			return;
		}

		myPreviousPage.reset();
		myNextPage.reset();
		boolean doRepaint = false;
		if (myCurrentPage.StartCursor.isNull()) {
			doRepaint = true;
			preparePaintInfo(myCurrentPage);
		}
		if (myCurrentPage.StartCursor.isNull()) {
			return;
		}
		if (myCurrentPage.StartCursor.getParagraphIndex() != mark.ParagraphIndex ||
			myCurrentPage.StartCursor.getMark().compareTo(mark) > 0) {
			doRepaint = true;
			gotoPosition(mark.ParagraphIndex, 0, 0);
			preparePaintInfo(myCurrentPage);
		}
		if (myCurrentPage.EndCursor.isNull()) {
			preparePaintInfo(myCurrentPage);
		}
		while (mark.compareTo(myCurrentPage.EndCursor.getMark()) > 0) {
			doRepaint = true;
			turnPage(true, ScrollingMode.NO_OVERLAPPING, 0);
			preparePaintInfo(myCurrentPage);
		}
		if (doRepaint) {
			if (myCurrentPage.StartCursor.isNull()) {
				preparePaintInfo(myCurrentPage);
			}
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	public synchronized void gotoHighlighting(ZLTextHighlighting highlighting) {
		myPreviousPage.reset();
		myNextPage.reset();
		boolean doRepaint = false;
		if (myCurrentPage.StartCursor.isNull()) {
			doRepaint = true;
			preparePaintInfo(myCurrentPage);
		}
		if (myCurrentPage.StartCursor.isNull()) {
			return;
		}
		if (!highlighting.intersects(myCurrentPage)) {
			gotoPosition(highlighting.getStartPosition().getParagraphIndex(), 0, 0);
			preparePaintInfo(myCurrentPage);
		}
		if (myCurrentPage.EndCursor.isNull()) {
			preparePaintInfo(myCurrentPage);
		}
		while (!highlighting.intersects(myCurrentPage)) {
			doRepaint = true;
			turnPage(true, ScrollingMode.NO_OVERLAPPING, 0);
			preparePaintInfo(myCurrentPage);
		}
		if (doRepaint) {
			if (myCurrentPage.StartCursor.isNull()) {
				preparePaintInfo(myCurrentPage);
			}
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	public synchronized int search(final String text, boolean ignoreCase, boolean wholeText, boolean backward, boolean thisSectionOnly) {
		if (myModel == null || text.length() == 0) {
			return 0;
		}
		int startIndex = 0;
		int endIndex = myModel.getParagraphsNumber();
		if (thisSectionOnly) {
			// TODO: implement
		}
		int count = myModel.search(text, startIndex, endIndex, ignoreCase);
		myPreviousPage.reset();
		myNextPage.reset();
		if (!myCurrentPage.StartCursor.isNull()) {
			rebuildPaintInfo();
			if (count > 0) {
				ZLTextMark mark = myCurrentPage.StartCursor.getMark();
				gotoMark(wholeText ?
					(backward ? myModel.getLastMark() : myModel.getFirstMark()) :
					(backward ? myModel.getPreviousMark(mark) : myModel.getNextMark(mark)));
			}
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
		return count;
	}

	public boolean canFindNext() {
		final ZLTextWordCursor end = isGuji()?myCurrentPage.StartCursor:myCurrentPage.EndCursor;
		return !end.isNull() && (myModel != null) && (
				isGuji()?myModel.getPreviousMark(end.getMark()) != null:myModel.getNextMark(end.getMark()) != null);
	}

	public synchronized void findNext() {
		final ZLTextWordCursor end = isGuji()?myCurrentPage.StartCursor:myCurrentPage.EndCursor;
		if (!end.isNull()) {
			gotoMark(isGuji()?myModel.getPreviousMark(end.getMark()):myModel.getNextMark(end.getMark()));
		}
	}

	public boolean canFindPrevious() {
		final ZLTextWordCursor start = isGuji()?myCurrentPage.EndCursor:myCurrentPage.StartCursor;
		return !start.isNull() && (myModel != null) && (
				isGuji()?myModel.getNextMark(start.getMark()) != null:
				myModel.getPreviousMark(start.getMark()) != null);
	}

	public synchronized void findPrevious() {
		final ZLTextWordCursor start = isGuji()?myCurrentPage.EndCursor:myCurrentPage.StartCursor;
		if (!start.isNull()) {
			gotoMark(isGuji()?myModel.getNextMark(start.getMark()):myModel.getPreviousMark(start.getMark()));
		}
	}

	public void clearFindResults() {
		if (!findResultsAreEmpty()) {
			myModel.removeAllMarks();
			rebuildPaintInfo();
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	public boolean findResultsAreEmpty() {
		return myModel == null || myModel.getMarks().isEmpty();
	}

	@Override
	public synchronized void onScrollingFinished(PageIndex pageIndex) {
		switch (pageIndex) {
			case current:
				break;
			case previous:
			{
				if(isDjvu()) {
					Application.DJVUDocument.currentPageIndex--;
					if(Application.DJVUDocument.currentPageIndex < 0) Application.DJVUDocument.currentPageIndex = 0;
					return;
				}
				final ZLTextPage swap = myNextPage;
				myNextPage = myCurrentPage;
				myCurrentPage = myPreviousPage;
				myPreviousPage = swap;
				myPreviousPage.reset();
				if (myCurrentPage.PaintState == PaintStateEnum.NOTHING_TO_PAINT) {
					preparePaintInfo(myNextPage);
					myCurrentPage.EndCursor.setCursor(myNextPage.StartCursor);
					myCurrentPage.PaintState = PaintStateEnum.END_IS_KNOWN;
				} else if (!myCurrentPage.EndCursor.isNull() &&
						   !myNextPage.StartCursor.isNull() &&
						   !myCurrentPage.EndCursor.samePositionAs(myNextPage.StartCursor)) {
					myNextPage.reset();
					myNextPage.StartCursor.setCursor(myCurrentPage.EndCursor);
					myNextPage.PaintState = PaintStateEnum.START_IS_KNOWN;
					Application.getViewWidget().reset();
				}
				break;
			}
			case next:
			{
				if(isDjvu()) {
					Application.DJVUDocument.currentPageIndex++;
					if(Application.DJVUDocument.currentPageIndex> Application.DJVUDocument.getPageCount() -1) Application.DJVUDocument.currentPageIndex = Application.DJVUDocument.getPageCount() -1;
					return;
				}
				final ZLTextPage swap = myPreviousPage;
				myPreviousPage = myCurrentPage;
				myCurrentPage = myNextPage;
				myNextPage = swap;
				myNextPage.reset();
				switch (myCurrentPage.PaintState) {
					case PaintStateEnum.NOTHING_TO_PAINT:
						preparePaintInfo(myPreviousPage);
						myCurrentPage.StartCursor.setCursor(myPreviousPage.EndCursor);
						myCurrentPage.PaintState = PaintStateEnum.START_IS_KNOWN;
						break;
					case PaintStateEnum.READY:
						myNextPage.StartCursor.setCursor(myCurrentPage.EndCursor);
						myNextPage.PaintState = PaintStateEnum.START_IS_KNOWN;
						break;
				}
				break;
			}
		}
	}

	public boolean removeHighlightings(Class<? extends ZLTextHighlighting> type) {
		boolean result = false;
		synchronized (myHighlightings) {
			for (Iterator<ZLTextHighlighting> it = myHighlightings.iterator(); it.hasNext(); ) {
				final ZLTextHighlighting h = it.next();
				if (type.isInstance(h)) {
					it.remove();
					result = true;
				}
			}
		}
		return result;
	}

	public void highlight(ZLTextPosition start, ZLTextPosition end) {
		removeHighlightings(ZLTextManualHighlighting.class);
		addHighlighting(new ZLTextManualHighlighting(this, start, end));
	}

	public final void addHighlighting(ZLTextHighlighting h) {
		myHighlightings.add(h);
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
	}

	public final void addHighlightings(Collection<ZLTextHighlighting> hilites) {
		myHighlightings.addAll(hilites);
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
	}

	public void clearHighlighting() {
		if (removeHighlightings(ZLTextManualHighlighting.class)) {
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	protected void moveSelectionCursorTo(SelectionCursor.Which which, int x, int y) {
		y -= getTextStyleCollection().getBaseStyle().getFontSize() / 2;
		mySelection.setCursorInMovement(which, x, y);
		mySelection.expandTo(myCurrentPage, x, y);
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
	}

	protected void releaseSelectionCursor() {
		mySelection.stop();
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
	}

	protected SelectionCursor.Which getSelectionCursorInMovement() {
		return mySelection.getCursorInMovement();
	}

	private ZLTextSelection.Point getSelectionCursorPoint(ZLTextPage page, SelectionCursor.Which which) {
		if (which == null) {
			return null;
		}

		if (which == mySelection.getCursorInMovement()) {
			return mySelection.getCursorInMovementPoint();
		}

		if (which == SelectionCursor.Which.Left) {
			if (mySelection.hasPartBeforePage(page)) {
				return null;
			}
			final ZLTextElementArea area = mySelection.getStartArea(page);
			if (area != null) {
				return new ZLTextSelection.Point(area.XStart, (area.YStart + area.YEnd) / 2);
			}
		} else {
			if (mySelection.hasPartAfterPage(page)) {
				return null;
			}
			final ZLTextElementArea area = mySelection.getEndArea(page);
			if (area != null) {
				return new ZLTextSelection.Point(area.XEnd, (area.YStart + area.YEnd) / 2);
			}
		}
		return null;
	}

	private float distance2ToCursor(int x, int y, SelectionCursor.Which which) {
		final ZLTextSelection.Point point = getSelectionCursorPoint(myCurrentPage, which);
		if (point == null) {
			return Float.MAX_VALUE;
		}
		final float dX = x - point.X;
		final float dY = y - point.Y;
		return dX * dX + dY * dY;
	}

	protected SelectionCursor.Which findSelectionCursor(int x, int y) {
		return findSelectionCursor(x, y, Float.MAX_VALUE);
	}

	protected SelectionCursor.Which findSelectionCursor(int x, int y, float maxDistance2) {
		if (mySelection.isEmpty()) {
			return null;
		}

		final float leftDistance2 = distance2ToCursor(x, y, SelectionCursor.Which.Left);
		final float rightDistance2 = distance2ToCursor(x, y, SelectionCursor.Which.Right);

		if (rightDistance2 < leftDistance2) {
			return rightDistance2 <= maxDistance2 ? SelectionCursor.Which.Right : null;
		} else {
			return leftDistance2 <= maxDistance2 ? SelectionCursor.Which.Left : null;
		}
	}

	private void drawSelectionCursor(ZLPaintContext context, ZLTextPage page, SelectionCursor.Which which) {
		final ZLTextSelection.Point pt = getSelectionCursorPoint(page, which);
		if (pt != null) {
			SelectionCursor.draw(context, which, pt.X, pt.Y, getSelectionBackgroundColor());
		}
	}
	
	private PagePosition PAGENO;
	String convertNumToChineseNum(int value) {
		if (value < 0) return "";
		String ChineseNum = "";
		String[] ChineseNums = new String[] { "???", "???", "???", "???", "???", "???",
				"???", "???", "???", "???" };
		String[] JinWei = new String[] { "", "???", "???", "???", "???", "???", "???", "???",
				"???" };
		ArrayList<Integer> list = new ArrayList<Integer>();
		while (value != 0) {
			list.add(value % 10);
			value = value / 10;
		}
		for (int i = list.size() - 1; i >= 0; i--) {
			int j = list.get(i).intValue();

			if (j != 0 && i != 4) {
				if (list.size() == 2 && i == 1 && j == 1) {
					ChineseNum += JinWei[i];
				} else {
					ChineseNum += ChineseNums[j] + JinWei[i];
				}
			} else if (j == 0 && i == 4) {
				ChineseNum += JinWei[4];
			}
		}
		return ChineseNum;
	}

	public void drawGujiBanxinTitle(ZLPaintContext context, int titleStartX, int gujiBanxinStartY, Boolean3 languageType) {
		setTextStyle(getTextStyleCollection().getBaseStyle());
		context.setTextColor(Application.ViewOptions.GujiBanxinColorOption.getValue());
		context.drawStringWithGujiRotatedCanvas(titleStartX,
				gujiBanxinStartY+this.getGujiBanxinHeight()/2+this.getFontSize()/2
				, myBook.getTitle(), true, languageType);
	}
	
	public void drawGujiBanxinYuwei(ZLPaintContext context, int yuweiStartX, int yuweiWidth, int gujiBanxinYStart, int gujiBanxinYEnd, int spaceWidth) {
		yuweiStartX += spaceWidth;
		int[] xs = new int[] {
				yuweiStartX, 
				yuweiStartX+yuweiWidth,
				yuweiStartX+yuweiWidth/2,
				yuweiStartX+yuweiWidth,
				yuweiStartX,
				};
		int[] ys = new int[] {
				gujiBanxinYStart,
				gujiBanxinYStart,
				(gujiBanxinYStart + gujiBanxinYEnd)/2,
				gujiBanxinYEnd,
				gujiBanxinYEnd,
				};
		//context.setFillColor(getTextColor(getTextStyle().Hyperlink));
        context.setFillShader(true);
		context.fillPolygon(xs, ys);
        context.setFillShader(false);

		context.drawLine(yuweiStartX - spaceWidth, gujiBanxinYStart, yuweiStartX - spaceWidth, gujiBanxinYEnd);
		context.drawLine(yuweiStartX + yuweiWidth + spaceWidth, gujiBanxinYStart, yuweiStartX+yuweiWidth/2 + spaceWidth, (gujiBanxinYStart + gujiBanxinYEnd)/2);
		context.drawLine(yuweiStartX+yuweiWidth/2 + spaceWidth, (gujiBanxinYStart + gujiBanxinYEnd)/2, yuweiStartX + yuweiWidth + spaceWidth, gujiBanxinYEnd);
	}
	public void drawGujiBanxinChapterTitle(ZLTextPage page, ZLPaintContext context, int chapterTitleStartX, int gujiBanxinYEnd, int length, Boolean3 languageType) {
		TOCTree treeToSelect = Application.getCurrentTOCElement(page);
		if(length <= 0) length = 15;
		if(treeToSelect != null) {
			context.drawStringWithGujiRotatedCanvas(chapterTitleStartX,
					gujiBanxinYEnd, treeToSelect.getText().substring(0, 
							Math.min(treeToSelect.getText().length(),length)),true, languageType);
		}
	}
	public void drawGujibanxinPagenum(ZLPaintContext context, int pageNo, int pageNumEndX, int gujiBanxinYStart) {
		final ZLTextNGStyleDescription description =
				getTextStyleCollection().getDescription(FBTextKind.GUJI_TRANSLATION);
		if (description != null) {
			setTextStyle(new ZLTextNGStyle(getTextStyle(), description, null));
		}
		
		String pageNum = convertNumToChineseNum(pageNo);
		context.drawStringWithGujiRotatedCanvas(pageNumEndX - context.getStringWidth(pageNum),
				gujiBanxinYStart+this.getFontSize()
				, pageNum, true, Boolean3.UNDEFINED);
	}
	
	public void drawGujiBanxinXiaYuwei(ZLPaintContext context, int xiaYuweiStartX, int gujiBanxinYStart, int gujiBanxinYEnd) {
		context.setLineWidth(2);
		context.drawLine(xiaYuweiStartX, gujiBanxinYStart, xiaYuweiStartX, gujiBanxinYEnd);
	}
	
	public void drawGujiJielan(ZLPaintContext context, int gujiTopLineNum, int gujiBottomLineNum, int gujiLineHeight, int gujiBanxinYEnd, ZLTextPage page) {
		int i = 1;
		int neiWidth = getGujiNeiBankuangWidth();
		context.setLineWidth(neiWidth);
		//context.setLineEffect(new DiscretePathEffect(this.getFontSize()*2, 2.0f), null);
		//Bitmap bmp = getGujiJielan(null);
		for(int y= 1;y<gujiTopLineNum;y++) {
			
			int intent = i<page.LineInfos.size()?page.LineInfos.get(i).LeftIndent:0;
			if(intent >0) intent = 0;
			//context.setLineColor(new ZLColor(255,0,0));
			context.drawLine(getLeftMargin()+getGujiLeftBankuangWidth()+intent, 
					y*gujiLineHeight+getTopMargin()+getGujiTopBankuangWidth(), 
					getRightLine(), 
					y*gujiLineHeight+getTopMargin()+getGujiTopBankuangWidth());

//			context.drawImage(getLeftMargin()+getGujiLeftBankuangWidth()+intent, 
//					y*gujiLineHeight+getTopMargin()+getGujiTopBankuangWidth(), 
//					bmp,null, ScalingType.OriginalSize, ColorAdjustingMode.NONE);
			i++;
		}
		
		if(gujiTopLineNum > 0) {
			i++;
		}
		for(int y= 1;y<gujiBottomLineNum;y++) {
			int intent = i<page.LineInfos.size()?page.LineInfos.get(i).LeftIndent:0;
			if(intent >0) intent = 0;
			//context.setLineColor(new ZLColor(255,0,0));
			context.drawLine(getLeftMargin()+getGujiLeftBankuangWidth()+intent,
					y*gujiLineHeight+gujiBanxinYEnd, 
					getRightLine(), 
					y*gujiLineHeight+gujiBanxinYEnd);
			i++;
		}
		//context.setLineEffect(null, null);
	}
	public void drawGujiBankuang(ZLPaintContext context, int waiBankuangStartY, int waiBankuangEndY) {
		int waiBanKuangWidth = getGujiWaiBankuangWidth();
		context.setLineWidth(waiBanKuangWidth);
		context.setLineColor(getTextColor(getTextStyle().Hyperlink));
		context.drawRectangle(getLeftMargin(), waiBankuangStartY, 
				getContextWidth()-getRightMargin(), waiBankuangEndY);	
		
		int neiBanKuangSpace = waiBanKuangWidth +getGujiSpaceBetweenBankuang();
		context.setLineWidth(getGujiNeiBankuangWidth());
		context.drawRectangle(
				getLeftMargin()+(Application.ViewOptions.DoubleTopBankuangOption.getValue()? neiBanKuangSpace:0), 
				waiBankuangStartY+(Application.ViewOptions.DoubleRightBankuangOption.getValue()? neiBanKuangSpace:0),
				getContextWidth()-getRightMargin()-(Application.ViewOptions.DoubleBottomBankuangOption.getValue()? neiBanKuangSpace:0), 
				waiBankuangEndY-(Application.ViewOptions.DoubleLeftBankuangOption.getValue()? neiBanKuangSpace:0));
	}
	
	public void drawGujiBankuang(ZLPaintContext context, ZLTextPage page, ArrayList<Integer> neiX, ArrayList<Integer> neiY) {
		int waiBanKuangWidth = getGujiWaiBankuangWidth();
		context.setLineWidth(waiBanKuangWidth);
		context.setLineColor(getTextColor(getTextStyle().Hyperlink));
		int[] x = new int[page.gujiBanKuangX.size()];
		int[] y = new int[page.gujiBanKuangY.size()];
		for(int i = 0; i < x.length;i++) {
			x[i] = page.gujiBanKuangX.get(i).intValue();
			y[i] = page.gujiBanKuangY.get(i).intValue();
		}
//		context.drawRectangle(getLeftMargin(), waiBankuangStartY, 
//				getContextWidth()-getRightMargin(), waiBankuangEndY);	
		context.drawPolygonalLine(x, y);
		
		x = new int[neiX.size()];
		y = new int[neiY.size()];
		for(int i = 0; i < x.length;i++) {
			x[i] = neiX.get(i).intValue();
			y[i] = neiY.get(i).intValue();
		}
		
		context.setLineWidth(getGujiNeiBankuangWidth());
		context.drawPolygonalLine(x, y);
	}
	
	public void drawGujiBanxin(ZLTextPage page, ZLPaintContext context, int gujiBanxinYStart, int gujiBanxinYEnd, int pageNo) {
		context.setLineWidth(this.getGujiNeiBankuangWidth());
		context.setLineColor(getTextColor(getTextStyle().Hyperlink));
		context.drawLine(getLeftMargin()+getGujiLeftBankuangWidth() , 
				gujiBanxinYStart, 
				getRightLine(), 
				gujiBanxinYStart);
		
		context.drawLine(getLeftMargin()+getGujiLeftBankuangWidth() , 
				gujiBanxinYEnd, 
				getRightLine(), 
				gujiBanxinYEnd);
		
		Boolean3 type = Application.ViewOptions.ShowTranditionalOption.getValue();
		
		int titleStartX = getLeftMargin()+getGujiLeftBankuangWidth() + 2;
		drawGujiBanxinTitle(context, titleStartX, gujiBanxinYStart, type);
		
		int spaceWidth = this.getFontSize() / 9 + 1;
		int yuweiStartX = titleStartX + context.getStringWidth(myBook.getTitle()) + spaceWidth;
		int yuweiWidth = (gujiBanxinYEnd - gujiBanxinYStart)*2/3;
		drawGujiBanxinYuwei(context, yuweiStartX, yuweiWidth, gujiBanxinYStart, gujiBanxinYEnd, spaceWidth);
		
		int xiaYuweiStartX = getRightLine()-getContextWidth()/10;
		drawGujiBanxinXiaYuwei(context, xiaYuweiStartX, gujiBanxinYStart, gujiBanxinYEnd);
		
		int pageNumEndX = xiaYuweiStartX-spaceWidth;
		drawGujibanxinPagenum(context, pageNo, pageNumEndX, gujiBanxinYStart);
		
		int chapterTitleStartX = yuweiStartX + yuweiWidth + spaceWidth + 2;
		int chapterTitleLength = xiaYuweiStartX - chapterTitleStartX;
		drawGujiBanxinChapterTitle(page, context, chapterTitleStartX, gujiBanxinYEnd, chapterTitleLength/context.getStringHeight(), type);
	}
	
	public void drawGujiBaobeiLayout(ZLPaintContext context, int gujiBanxinYStart, int gujiBanxinYEnd, int gujiLineHeight, ZLTextPage page) {
		final int pageNo = page.PAGENO;
		int gujiLineNum = getGujiBaobeiLineNum();
		boolean isOddPage = false;
		if(pageNo % 2 == 1) {
			isOddPage = true;
		}
		
		drawGujiBanxin(page, context, gujiBanxinYStart, gujiBanxinYEnd, pageNo);
		if(Application.ViewOptions.ShowGujiJielanOption.getValue()) {
			drawGujiJielan(context, isOddPage?gujiLineNum:-1, isOddPage?-1:gujiLineNum, gujiLineHeight, gujiBanxinYEnd, page);
		}
		
		int waiBanKuangStartY = isOddPage?getTopMargin(): gujiBanxinYStart;
		int waiBanKuangEndY = isOddPage?gujiBanxinYEnd:getContextHeight()-getBottomMargin();
		int size = page.LineInfos.size();
		int lineNo = getGujiBaobeiLineNum();
		if(size !=lineNo && size > 0) {
			page.gujiBanKuangX.add(getLeftMargin());
			page.gujiBanKuangY.add(page.gujiBanKuangY.get(page.gujiBanKuangY.size()-1));
		}
		if(isOddPage) { // left bottom banxin
			page.gujiBanKuangX.add(getLeftMargin());
			page.gujiBanKuangY.add(gujiBanxinYStart);
		}
		
		ArrayList<Integer> x = new ArrayList<Integer>();
		ArrayList<Integer> y = new ArrayList<Integer>();
		int waiBanKuangWidth = getGujiWaiBankuangWidth();
		int neiBanKuangSpace = waiBanKuangWidth +getGujiSpaceBetweenBankuang();
		int leftWidth = (Application.ViewOptions.DoubleTopBankuangOption.getValue()? neiBanKuangSpace:0); 
		for(int i = 0;i < page.gujiBanKuangX.size();i++) {
			x.add(page.gujiBanKuangX.get(i)+leftWidth);
			if(i+1<page.gujiBanKuangX.size() && page.gujiBanKuangY.get(i+1).intValue() == page.gujiBanKuangY.get(i).intValue()) {
				if(page.gujiBanKuangX.get(i+1).intValue() > page.gujiBanKuangX.get(i).intValue()) {
					page.gujiBanKuangY.set(i, page.gujiBanKuangY.get(i)+neiBanKuangSpace);
					page.gujiBanKuangY.set(i+1, page.gujiBanKuangY.get(i+1)+neiBanKuangSpace);
				} else if(page.gujiBanKuangX.get(i+1).intValue() < page.gujiBanKuangX.get(i).intValue()) {
					page.gujiBanKuangY.set(i, page.gujiBanKuangY.get(i)-neiBanKuangSpace);
					page.gujiBanKuangY.set(i+1, page.gujiBanKuangY.get(i+1)-neiBanKuangSpace);
				}
			}
			if(i+1<page.gujiBanKuangX.size() && page.gujiBanKuangY.get(i+1).intValue() == page.gujiBanKuangY.get(i).intValue() && 
					page.gujiBanKuangX.get(i+1).intValue() > page.gujiBanKuangX.get(i).intValue()) {
				y.add(page.gujiBanKuangY.get(i)-leftWidth);
				x.add(page.gujiBanKuangX.get(i+1)+leftWidth);
				y.add(page.gujiBanKuangY.get(i+1)-leftWidth);
				i++;
			} else {
				 if(i < page.gujiBanKuangX.size() - 1) {
					 y.add(page.gujiBanKuangY.get(i)+leftWidth);
				 } else {
					 y.add(page.gujiBanKuangY.get(i));
				 }
			}
		}
		
		int bottomWidth = Application.ViewOptions.DoubleLeftBankuangOption.getValue()? neiBanKuangSpace:0;
		int indent = 0;
		if(!isOddPage) {
			if(size - 1 > 0 && size == lineNo) {
				indent = page.LineInfos.get(size-1).LeftIndent < 0?page.LineInfos.get(size-1).LeftIndent:0;
			}
		}
		//left bottom
		page.gujiBanKuangX.add(getLeftMargin() + indent);
		page.gujiBanKuangY.add(waiBanKuangEndY);
		x.add(getLeftMargin() + indent+leftWidth);
		y.add((isOddPage&&(waiBanKuangEndY-bottomWidth < getContextHeight()))?getContextHeight():waiBanKuangEndY-bottomWidth);
		
		int rightWidth = Application.ViewOptions.DoubleBottomBankuangOption.getValue()? neiBanKuangSpace:0;
		//right, bottom
		page.gujiBanKuangX.add(getContextWidth() - getRightMargin());
		page.gujiBanKuangY.add(waiBanKuangEndY);
		x.add(getContextWidth() - getRightMargin()-rightWidth);
		y.add((isOddPage&&(waiBanKuangEndY-bottomWidth < getContextHeight()))?getContextHeight():waiBanKuangEndY-bottomWidth);
		int topWidth = Application.ViewOptions.DoubleRightBankuangOption.getValue()? neiBanKuangSpace:0;
		//right, top
		page.gujiBanKuangX.add(getContextWidth() - getRightMargin());
		page.gujiBanKuangY.add(waiBanKuangStartY);
		x.add(getContextWidth() - getRightMargin()-rightWidth);
		y.add((!isOddPage && waiBanKuangStartY+topWidth>0)?0:waiBanKuangStartY+topWidth);
		indent = 0;
		if(isOddPage && page.LineInfos.size() > 0) {
			 indent = page.LineInfos.get(0).LeftIndent < 0?page.LineInfos.get(0).LeftIndent:0;
		}
		// left, top
		page.gujiBanKuangX.add(getLeftMargin()+indent);
		page.gujiBanKuangY.add(waiBanKuangStartY);
		x.add(getLeftMargin()+indent+leftWidth);
		y.add((!isOddPage && waiBanKuangStartY+topWidth>0)?0:waiBanKuangStartY+topWidth);
		if(!isOddPage) {
			//left, top, banxin
			page.gujiBanKuangX.add(getLeftMargin());
			page.gujiBanKuangY.add(gujiBanxinYEnd);
			x.add(getLeftMargin()+leftWidth);
			y.add(gujiBanxinYEnd+leftWidth);
		}
		drawGujiBankuang(context, page,x,y);
	}
	
	public void drawGujiHudieLayout(ZLPaintContext context, int gujiBanxinYStart, int gujiBanxinYEnd, int gujiLineHeight, ZLTextPage page) {
		int gujiHalfLineNum = getGujiHudieHalfLineNum();
		if(PAGENO != null)
		drawGujiBanxin(page,context, gujiBanxinYStart, gujiBanxinYEnd, page.PAGENO);
		if(Application.ViewOptions.ShowGujiJielanOption.getValue()) {
			drawGujiJielan(context, gujiHalfLineNum, gujiHalfLineNum, gujiLineHeight, gujiBanxinYEnd, page);
		}
		
//		drawGujiBankuang(context, getTopMargin(), getContextHeight()-getBottomMargin());
		int waiBanKuangStartY = getTopMargin();
		int waiBanKuangEndY = getContextHeight()-getBottomMargin();
		int size = page.LineInfos.size();
		int lineNo = getGujiHudieHalfLineNum()*2;
		if(size !=lineNo && size > 0) {
			page.gujiBanKuangX.add(getLeftMargin());
			page.gujiBanKuangY.add(page.gujiBanKuangY.get(page.gujiBanKuangY.size()-1));
		}
		
		ArrayList<Integer> x = new ArrayList<Integer>();
		ArrayList<Integer> y = new ArrayList<Integer>();
		int waiBanKuangWidth = getGujiWaiBankuangWidth();
		int neiBanKuangSpace = waiBanKuangWidth +getGujiSpaceBetweenBankuang();
		int leftWidth = (Application.ViewOptions.DoubleTopBankuangOption.getValue()? neiBanKuangSpace:0); 
		for(int i = 0;i < page.gujiBanKuangX.size();i++) {
			x.add(page.gujiBanKuangX.get(i)+leftWidth);
			if(i+1<page.gujiBanKuangX.size() && page.gujiBanKuangY.get(i+1).intValue() == page.gujiBanKuangY.get(i).intValue()) {
				if(page.gujiBanKuangX.get(i+1).intValue() > page.gujiBanKuangX.get(i).intValue()) {
					page.gujiBanKuangY.set(i, page.gujiBanKuangY.get(i)+neiBanKuangSpace);
					page.gujiBanKuangY.set(i+1, page.gujiBanKuangY.get(i+1)+neiBanKuangSpace);
				} else if(page.gujiBanKuangX.get(i+1).intValue() < page.gujiBanKuangX.get(i).intValue()) {
					page.gujiBanKuangY.set(i, page.gujiBanKuangY.get(i)-neiBanKuangSpace);
					page.gujiBanKuangY.set(i+1, page.gujiBanKuangY.get(i+1)-neiBanKuangSpace);
				}
			}
			if(i+1<page.gujiBanKuangX.size() && page.gujiBanKuangY.get(i+1).intValue() == page.gujiBanKuangY.get(i).intValue() && 
					page.gujiBanKuangX.get(i+1).intValue() > page.gujiBanKuangX.get(i).intValue()) {
				y.add(page.gujiBanKuangY.get(i)-leftWidth);
				x.add(page.gujiBanKuangX.get(i+1)+leftWidth);
				y.add(page.gujiBanKuangY.get(i+1)-leftWidth);
				i++;
			} else {
				 if(i < page.gujiBanKuangX.size() - 1) {
					 y.add(page.gujiBanKuangY.get(i)+leftWidth);
				 } else {
					 y.add(page.gujiBanKuangY.get(i));
				 }
			}
		}
		
		int bottomWidth = Application.ViewOptions.DoubleLeftBankuangOption.getValue()? neiBanKuangSpace:0;
		int indent = 0;
		if(size - 1 > 0 && size == lineNo) {
			indent = page.LineInfos.get(size-1).LeftIndent < 0?page.LineInfos.get(size-1).LeftIndent:0;
		}
		
		//left bottom
		page.gujiBanKuangX.add(getLeftMargin() + indent);
		page.gujiBanKuangY.add(waiBanKuangEndY);
		x.add(getLeftMargin() + indent+leftWidth);
		y.add(waiBanKuangEndY-bottomWidth);
		
		int rightWidth = Application.ViewOptions.DoubleBottomBankuangOption.getValue()? neiBanKuangSpace:0;
		//right, bottom
		page.gujiBanKuangX.add(getContextWidth() - getRightMargin());
		page.gujiBanKuangY.add(waiBanKuangEndY);
		x.add(getContextWidth() - getRightMargin()-rightWidth);
		y.add(waiBanKuangEndY-bottomWidth);
		int topWidth = Application.ViewOptions.DoubleRightBankuangOption.getValue()? neiBanKuangSpace:0;
		//right, top
		page.gujiBanKuangX.add(getContextWidth() - getRightMargin());
		page.gujiBanKuangY.add(waiBanKuangStartY);
		x.add(getContextWidth() - getRightMargin()-rightWidth);
		y.add(waiBanKuangStartY+topWidth);
		indent = 0;
		indent = page.LineInfos.get(0).LeftIndent < 0?page.LineInfos.get(0).LeftIndent:0;
		
		// left, top
		page.gujiBanKuangX.add(getLeftMargin()+indent);
		page.gujiBanKuangY.add(waiBanKuangStartY);
		x.add(getLeftMargin()+indent+leftWidth);
		y.add(waiBanKuangStartY+topWidth);
		
		drawGujiBankuang(context, page,x,y);
		
	}
	
	public void drawGujiJingzheLayout(ZLPaintContext context, int gujiBanxinYStart, int gujiBanxinYEnd, int gujiLineHeight, ZLTextPage page) {
		
	}
	
	public void drawGujiInternalCover(ZLPaintContext context) {
		drawGujiBankuang(context, getTopMargin(), getContextHeight()-getBottomMargin());
		context.setLineWidth(getGujiNeiBankuangWidth());	
		//context.setLineColor(new ZLColor(255,0,0));
		int leftMargin = getLeftMargin()+getGujiLeftBankuangWidth();
		int rightMargin = getRightMargin() + getGujiRightBankuangWidth();
		int topMargin = getTopMargin() + getGujiTopBankuangWidth();
		int bottomMargin = getBottomMargin() + getGujiBottomBankuangWidth();
		int height = getContextHeight() - topMargin - bottomMargin;
		int width  = getContextWidth() - leftMargin - rightMargin;
		context.drawLine(leftMargin,
				topMargin + height/4, 
				getRightLine(), 
				topMargin + height/4);
		
		context.drawLine(getLeftMargin()+getGujiLeftBankuangWidth(), 
				topMargin + height*3/4, 
				getRightLine(), 
				topMargin + height*3/4);
		
		
		resetTextStyle();
		//context.setTextColor(Application.ViewOptions.GujiYiColorOption.getValue());
		if(myBook.authors() == null || myBook.authors().size() == 0) {
			context.drawStringWithGujiRotatedCanvas(getLeftMargin()+getGujiLeftBankuangWidth(), 
					topMargin + getContext().getStringHeight(), 
					"?????????", true, Boolean3.UNDEFINED);
		} else {
			int authorNum = height/(4*getContext().getStringHeight());
			for(int i = 0; i < myBook.authors().size(); i++) {
				authorNum--;
				context.drawStringWithGujiRotatedCanvas(getLeftMargin()+getGujiLeftBankuangWidth(), 
						topMargin + (i+1) * getContext().getStringHeight(), 
						myBook.authors().get(i).DisplayName, true, Boolean3.UNDEFINED);
				if(authorNum == 0) {
					break;
				}
			}
		}
		context.drawStringWithGujiRotatedCanvas(getRightLine() - 6*getContext().getStringHeight(), 
				topMargin + height*7 /8 + getContext().getStringHeight()/2, 
				"??????????????????", true, Boolean3.UNDEFINED);
		
		int titleFontSize = Math.min(width/myBook.getTitle().length(), height /2);
		getContext().setTextSize(titleFontSize);
		context.drawStringWithGujiRotatedCanvas(getLeftMargin()+getGujiLeftBankuangWidth(),  topMargin + height /2 + titleFontSize/2, 
				myBook.getTitle(), true, Boolean3.UNDEFINED);
		
		int size = (int)(Math.min(getContextWidth(),getContextHeight())*0.13);
		context.drawImage(leftMargin, topMargin + height /2, getGujiYinZhang(new Size(size, size)), null, ScalingType.OriginalSize, ColorAdjustingMode.NONE);
	}
	
	public void drawGujiCover(ZLPaintContext context) {
		final ZLibrary zlibrary = ZLibrary.Instance();
		final int dpi = zlibrary.getDisplayDPI();
		final int x = zlibrary.getWidthInPixels();
		final int y = zlibrary.getHeightInPixels();
		final int horMargin = Math.min(dpi / 5, Math.min(x, y) / 30);
		
		int lineWidth = horMargin/3+1;
		int startY = (int)(getContextHeight()*0.77f);
		int endX = (int)(getContextWidth()*0.8f);
		JniBitmapHolder coverHolder = getGujiCover();
		if(coverHolder != null) {
			Bitmap bitmap = coverHolder.getBitmap();
			if(bitmap != null) {
				context.drawImage(1, 1, bitmap, null, ScalingType.OriginalSize, ColorAdjustingMode.NONE);
				bitmap.recycle();
			}
		} else {
		
			context.setFillColor(new ZLColor(55,74,116));
			context.fillRectangle(0, 0, getContextWidth(), getContextHeight());
			
			
			context.setFillColor(new ZLColor(255,255,255));
			context.fillRectangle(10, startY, endX, getContextHeight()-10);
			
			context.setLineWidth(lineWidth);
			context.drawRectangle(10+lineWidth, startY+(lineWidth), 
					endX-(lineWidth), 
					getContextHeight()-10-(lineWidth));
			
			int verticalLinePos = getContextHeight()/20;
			int horLinePos = getContextWidth() / 30;
			context.setLineWidth(lineWidth);
			context.setLineColor(new ZLColor(255,255,255));
			context.drawLine(0, verticalLinePos, getContextWidth(), verticalLinePos);
			context.drawLine(horLinePos, 0, horLinePos, verticalLinePos);
			context.drawLine(getContextWidth()-horLinePos, 0, getContextWidth()-horLinePos, verticalLinePos);
			int distance = (getContextWidth()-horLinePos*2)/3;
			context.drawLine(horLinePos+distance, 0, horLinePos+distance, verticalLinePos);
			context.drawLine(horLinePos+2*distance, 0, horLinePos+2*distance, verticalLinePos);
		}
			
		Boolean3 type = Application.ViewOptions.ShowTranditionalOption.getValue();
		
		ZLTextNGStyleDescription description =
				getTextStyleCollection().getDescription(FBTextKind.TITLE);
		if (description != null) {
			setTextStyle(new ZLTextNGStyle(getTextStyle(), description, null));
		}
		context.setTextColor(new ZLColor(0,0,0));
		context.drawStringWithGujiRotatedCanvas(getContextWidth()*72/1920,
				(int)(getContextHeight()*0.88f)+this.getFontSize()/2
				, myBook.getTitle(),true, type);
		int titleWidth = context.getStringWidth(myBook.getTitle());
		
		description =
				getTextStyleCollection().getDescription(FBTextKind.GUJI_TRANSLATION);
		if (description != null) {
			setTextStyle(new ZLTextNGStyle(getTextStyle(), description, null));
		}
		
		context.setTextColor(Application.ViewOptions.GujiBanxinColorOption.getValue());
		if(myBook.authors().size() == 0) {
			context.drawStringWithGujiRotatedCanvas(10+lineWidth+30+titleWidth+20,
					(int)(getContextHeight()*0.88f)
					, "?????????",true, type);
			context.drawStringWithGujiRotatedCanvas(10+lineWidth+30+titleWidth+20,
					(int)(getContextHeight()*0.88f)+this.getFontSize()
					, "??????",true, type);
		} else {
			int authorNum = 2;
			for(int i = 0; i < myBook.authors().size(); i++) {
				authorNum--;
				context.drawStringWithGujiRotatedCanvas(10+lineWidth+30+titleWidth+20,
						(int)(getContextHeight()*0.87f) + i*this.getFontSize()
						, myBook.authors().get(i).DisplayName,true, type);
				if(authorNum == 0) {
					break;
				}
			}
		}
		
		if(coverHolder == null) {
			int size = (int)(getContextHeight()*0.13);
			context.drawImage(endX - size, startY, 
					getGujiYinZhang(new Size(size, size)), 
					null, 
					ScalingType.OriginalSize, ColorAdjustingMode.NONE);
		}
	}
	
	int getBottomLine(ZLTextPage page) {
		int height = getContextHeight();
		if(isGuji()) {
			GujiLayoutStyleEnum gujiStyle = getGujiStyle();
			if(gujiStyle == GujiLayoutStyleEnum.baobei) {
				final int pageNo = page.PAGENO;
				boolean isOddPage = false;
				if(pageNo % 2 == 1) {
					isOddPage = true;
				}
				if(isOddPage) {
					height = height - this.getGujiBanxinHeight() / 2;
				} else {
					height = height - getBottomMargin() - getGujiBottomBankuangWidth();
				}
			} else {
				height = height - getBottomMargin() - getGujiBottomBankuangWidth();
			}
		} else {
			height = height - getBottomMargin();
		}
		return height;
	}
	
	public int getTextAreaHeight() {
		int height = getContextHeight(); 
		if(isGuji()) {
			if(myTextAreaHeight != -1) return myTextAreaHeight;
			GujiLayoutStyleEnum gujiStyle = getGujiStyle();
			if(gujiStyle == GujiLayoutStyleEnum.baobei) {
				if(PAGENO == null) return 0;
				final int pageNo = myCurrentPage.PAGENO;
				boolean isOddPage = false;
				if(pageNo % 2 == 1) {
					isOddPage = true;
				}
				if(isOddPage) {
					height = height - getTopMargin() - getGujiTopBankuangWidth() - getGujiBanxinHeight() /2;
				} else {
					height = height - getBottomMargin() - getGujiBottomBankuangWidth() - getGujiBanxinHeight() /2;
				}
			} else {
				height = height - getTopMargin() - getBottomMargin() - getGujiTopBankuangWidth() - getGujiBottomBankuangWidth() - getGujiBanxinHeight();
			}
			myTextAreaHeight = height;
		} else {
			height = height - getTopMargin() - getBottomMargin();
		}
		return height;
	}
	
	public void drawWallpaper(ZLPaintContext context, PageIndex pageIndex, ZLTextPage page) {
		ZLFile wallpaper = getWallpaperFile();
		if(wallpaper != null) {
			if(isGuji()) {
				if(isLandscape()) {
					context.clear(ZLFile.createFileByPath("wallpapers/guji_bg.jpg"), 
								ZLFile.createFileByPath("wallpapers/guji_bg.jpg"), ZLPaintContext.FillMode.fullscreen);
					context.drawShadow(getContextHeight()/2, 0,
							getContextHeight()/2 + getGujiBanxinHeight(), getContextWidth(), true);
                    context.drawShadow(getContextHeight()/2- getGujiBanxinHeight()/2,0,
							getContextHeight()/2 , getContextWidth(), false);
				} else {
					context.clear(wallpaper, getFillMode());
					if(PAGENO == null) return;
					if(getGujiStyle() == GujiLayoutStyleEnum.baobei) {
						final int pageNo = page.PAGENO;
						boolean isOddPage = false;
						if(pageNo % 2 == 1) {
							isOddPage = true;
						}
						if(!isOddPage) {
							context.drawShadow(0,0, 100*getContextHeight()/1080, getContextWidth(), true);
						} else {
							context.drawShadow(getContextHeight() - 60*getContextHeight()/1080,0,
									getContextHeight(), getContextWidth(), false);
						}
					}
				}
			} else {
				context.clear(wallpaper, getFillMode());
			}
		} else {
			context.clear(getBackgroundColor());
		}
	}
	
	@Override
	public synchronized void preparePage(ZLPaintContext context, PageIndex pageIndex) {
		setContext(context);
		
		preparePaintInfo(getPage(pageIndex));
	}

	@Override
	public synchronized void paint(ZLPaintContext context, PageIndex pageIndex) {
		setContext(context);
		
		ZLTextPage page = getPage(pageIndex);
		if(isGuji()) {
			switch (pageIndex) {
			default:
			case current:
				if(page.PAGENO == -1 && PAGENO != null) {
					page.PAGENO = PAGENO.Current;
				}
				break;
			case previous:
				if(page.PAGENO == -1 && myCurrentPage != null) {
					page.PAGENO = Math.abs(myCurrentPage.PAGENO - 1);
				}
				break;
			case next:
				if(page.PAGENO == -1 && myCurrentPage != null) {
					page.PAGENO = myCurrentPage.PAGENO + 1;
				}
			}
		}
		drawWallpaper(context, pageIndex, page);

		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return;
		}
		
		if(isGuji()) {
			// keep the screen from translating or rotating afterwards.
			context.getCanvas().save();
			// Move the coordination to the top-right;
			context.getCanvas().translate(getContextHeight(),0);
			context.getCanvas().rotate(90);
		}

		switch (pageIndex) {
			default:
			case current:
				break;
			case previous:
				if (myPreviousPage.PaintState == PaintStateEnum.NOTHING_TO_PAINT) {
					preparePaintInfo(myCurrentPage);
					myPreviousPage.EndCursor.setCursor(myCurrentPage.StartCursor);
					myPreviousPage.PaintState = PaintStateEnum.END_IS_KNOWN;
				}
				break;
			case next:
				if (myNextPage.PaintState == PaintStateEnum.NOTHING_TO_PAINT) {
					preparePaintInfo(myCurrentPage);
					myNextPage.StartCursor.setCursor(myCurrentPage.EndCursor);
					myNextPage.PaintState = PaintStateEnum.START_IS_KNOWN;
				}
		}
		
		page.TextElementMap.clear();

		setTextStyle(getTextStyleCollection().getBaseStyle());
		preparePaintInfo(page);

		if (page.StartCursor.isNull() || page.EndCursor.isNull()) {
			return;
		}
		
		int x = getLeftMargin();
		int y = getTopMargin();
		int gujiBanxinYStart = 0;
		int gujiBanxinYEnd = 0;
		int gujiLineHeight = 0;
		if(this.isGuji()) {
			if(page.StartCursor.getParagraphIndex() == 0) {
				drawGujiCover(context);
			} else if(isGujiAuthorParagraph(page.StartCursor)) {
				drawGujiInternalCover(context);
			} else {
				GujiLayoutStyleEnum gujiStyle = getGujiStyle();
				if(gujiStyle == GujiLayoutStyleEnum.baobei) {
					if(PAGENO == null) return;
					final int pageNo = page.PAGENO;
					boolean isOddPage = false;
					if(pageNo % 2 == 1) {
						isOddPage = true;
					}
					if(isOddPage) {
						x = getLeftMargin() + getGujiLeftBankuangWidth();
						y = getTopMargin() + getGujiTopBankuangWidth();
						gujiBanxinYStart = this.getContextHeight() - this.getGujiBanxinHeight() / 2;
						gujiBanxinYEnd = this.getContextHeight() + this.getGujiBanxinHeight() / 2;
					} else {
						gujiBanxinYStart = -1 * this.getGujiBanxinHeight() / 2;
						gujiBanxinYEnd = this.getGujiBanxinHeight() / 2;
						x = getLeftMargin() + getGujiLeftBankuangWidth();
						y = gujiBanxinYEnd;
					}
					gujiLineHeight = getGujiBaobeiLineHeight();
					//drawGujiBaobeiLayout(context, gujiBanxinYStart, gujiBanxinYEnd, gujiLineHeight, page);
				} else if(gujiStyle == GujiLayoutStyleEnum.jingzhe) {
					x = getLeftMargin() + getGujiLeftBankuangWidth();
					y = getTopMargin() + getGujiTopBankuangWidth();
					gujiBanxinYStart = getTextAreaHeight() /2+getTopMargin()+getGujiTopBankuangWidth();
					gujiBanxinYEnd = gujiBanxinYStart + this.getGujiBanxinHeight();
					gujiLineHeight = getGujiHudieLineHeight();
					drawGujiJingzheLayout(context, gujiBanxinYStart, gujiBanxinYEnd, gujiLineHeight, page);
				}  else {
					x = getLeftMargin() + getGujiLeftBankuangWidth();
					y = getTopMargin() + getGujiTopBankuangWidth();
					gujiBanxinYStart = getTextAreaHeight() /2+getTopMargin()+getGujiTopBankuangWidth();
					gujiBanxinYEnd = gujiBanxinYStart + this.getGujiBanxinHeight();
					gujiLineHeight = getGujiHudieLineHeight();
					//drawGujiHudieLayout(context, gujiBanxinYStart, gujiBanxinYEnd, gujiLineHeight, page);
				}
			}
		} else if(!Application.ViewOptions.HeaderHidden.getValue()){
			context.setTextColor(new ZLColor(128,128,128));
			int oldSize = this.getFontSize();
			context.setTextSize(Application.ViewOptions.HeaderHeight.getValue());
			TOCTree treeToSelect = Application.getCurrentTOCElement(page);
			if(treeToSelect != null) {
				Boolean3 type = Boolean3.UNDEFINED;
				if(myBook != null && myBook.getLanguage() != null && myBook.getLanguage().startsWith("zh")) {
					type = Application.ViewOptions.ShowTranditionalOption.getValue();
				}
				context.drawStringWithGujiRotatedCanvas(getLeftMargin(), 36, treeToSelect.getText(),false, type);
			}
			context.setTextSize(oldSize);
		}
		
		final ArrayList<ZLTextLineInfo> lineInfos = page.LineInfos;
		final int[] labels = new int[lineInfos.size() + 1];
		
		int startY = y;
		int index = 0;
		int columnIndex = 0;
		ZLTextLineInfo previousInfo = null;
		if(isGujiCoverParagraph(page.StartCursor)) {
			
		} else {
			page.gujiBanKuangX.clear();
			page.gujiBanKuangY.clear();
			for (ZLTextLineInfo info : lineInfos) {
				info.adjust(previousInfo);
				int leftIndent = info.LeftIndent<0?info.LeftIndent:0;
				page.gujiBanKuangX.add(getLeftMargin()+leftIndent);
				page.gujiBanKuangY.add(y);
				prepareTextLine(page, info, x, y, columnIndex);
				y += isGuji() ? gujiLineHeight : info.Height + info.Descent + info.VSpaceAfter;
				//y is close to gujiBanxinYstart but above gujiBanxinYStart
				if(y>(gujiBanxinYStart-gujiLineHeight) && y< gujiBanxinYEnd && isGuji()) {
					GujiLayoutStyleEnum gujiStyle = getGujiStyle();
					if(gujiStyle == GujiLayoutStyleEnum.baobei) {
						y = gujiBanxinYStart;
					} else {
						if(gujiStyle == GujiLayoutStyleEnum.hudie) {
							page.gujiBanKuangX.add(getLeftMargin()+leftIndent);
							page.gujiBanKuangY.add(gujiBanxinYStart);
							page.gujiBanKuangX.add(getLeftMargin());
							page.gujiBanKuangY.add(gujiBanxinYStart);
							leftIndent = 0;
						}
						y = gujiBanxinYEnd;
					}
				}
				page.gujiBanKuangX.add(getLeftMargin()+leftIndent);
				page.gujiBanKuangY.add(y);
				
				labels[++index] = page.TextElementMap.size();
				if (index == page.Column0Height) {
					y = startY;
					x += page.getTextWidth() + getSpaceBetweenColumns();
					columnIndex = 1;
				}
				previousInfo = info;
			}
			if(isGuji()) {
				GujiLayoutStyleEnum gujiStyle = getGujiStyle();
				if(gujiStyle == GujiLayoutStyleEnum.baobei) {
					drawGujiBaobeiLayout( context,  gujiBanxinYStart,  gujiBanxinYEnd,  gujiLineHeight,  page);
				} else if(gujiStyle == GujiLayoutStyleEnum.hudie) {
					drawGujiHudieLayout(context, gujiBanxinYStart, gujiBanxinYEnd, gujiLineHeight, page);
				}
			}
		}
		

		if(isGuji()) {
			context.getCanvas().rotate(-90);
		}
		final List<ZLTextHighlighting> hilites = findHilites(page);
		if(isGujiCoverParagraph(page.StartCursor)) {
			
		} else {
			//draw section title on the blank page
			if(isGuji() && lineInfos.size() == 0) {
				TOCTree treeToSelect = Application.getCurrentTOCElement(page);
				if(treeToSelect != null) {
					resetTextStyle();
					context.setTextColor(getTextColor(getTextStyle().Hyperlink));
					context.drawString(x, getBottomLine(page), treeToSelect.getText().toCharArray(), 
							0, treeToSelect.getText().length(), true, Boolean3.UNDEFINED);
				}
				
			} else {
				index = 0;
				for (ZLTextLineInfo info : lineInfos) {
					drawTextLine(page, hilites, info, labels[index], labels[index + 1]);
					y += isGuji() ? gujiLineHeight : info.Height + info.Descent
							+ info.VSpaceAfter;
					if (y > (gujiBanxinYStart - gujiLineHeight)
							&& y < gujiBanxinYEnd) {
						y = gujiBanxinYEnd;
					}
					++index;
					if (index == page.Column0Height) {
						y = startY;
						x += page.getTextWidth() + getSpaceBetweenColumns();
					}
				}
			}
		}
		if(isGuji()) {
			context.getCanvas().rotate(90);
		}

		for (ZLTextHighlighting h : hilites) {
			int mode = Hull.DrawMode.None;

			final ZLColor bgColor = h.getBackgroundColor();
			if (bgColor != null) {
				context.setFillColor(bgColor, 128);
				mode |= Hull.DrawMode.Fill;
			}

			final ZLColor outlineColor = h.getOutlineColor();
			if (outlineColor != null) {
				context.setLineColor(outlineColor);
				mode |= Hull.DrawMode.Outline;
			}

			if (mode != Hull.DrawMode.None) {
				h.hull(page).draw(getContext(), mode);
			}
		}

		final ZLTextRegion outlinedElementRegion = getOutlinedRegion(page);
		if (outlinedElementRegion != null && myShowOutline) {
			context.setLineColor(getSelectionBackgroundColor());
			outlinedElementRegion.hull().draw(context, Hull.DrawMode.Outline);
		}

		drawSelectionCursor(context, page, SelectionCursor.Which.Left);
		drawSelectionCursor(context, page, SelectionCursor.Which.Right);
		if(isGuji()) {
			if(page.StartCursor.getParagraphIndex() > 1) {
				int size = (int)(Math.min(getContextWidth(),getContextHeight())*0.13);
				context.drawImage(30*getContextHeight()/1080, 230*getContextWidth()/1920, getGujiYinZhang(new Size(size, size)), null, ScalingType.OriginalSize, ColorAdjustingMode.NONE);
				
			}
			try {
			context.getCanvas().restore();
			} catch(Exception ex) {
				
			}
		}
		
	}

	public ZLTextPage getPage(PageIndex pageIndex) {
		switch (pageIndex) {
			default:
			case current:
				return myCurrentPage;
			case previous:
				return myPreviousPage;
			case next:
				return myNextPage;
		}
	}

	public static final int SCROLLBAR_HIDE = 0;
	public static final int SCROLLBAR_SHOW = 1;
	public static final int SCROLLBAR_SHOW_AS_PROGRESS = 2;

	public abstract int scrollbarType();

	@Override
	public final boolean isScrollbarShown() {
		if(isGuji()) return false;
		return scrollbarType() == SCROLLBAR_SHOW || scrollbarType() == SCROLLBAR_SHOW_AS_PROGRESS;
	}

	protected final synchronized int sizeOfTextBeforeParagraph(int paragraphIndex) {
		return myModel != null ? myModel.getTextLength(paragraphIndex - 1) : 0;
	}

	protected final synchronized int sizeOfFullText() {
		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return 1;
		}
		return myModel.getTextLength(myModel.getParagraphsNumber() - 1);
	}

	private final synchronized int getCurrentCharNumber(PageIndex pageIndex, boolean startNotEndOfPage) {
		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return 0;
		}
		final ZLTextPage page = getPage(pageIndex);
		preparePaintInfo(page);
		if (startNotEndOfPage) {
			return Math.max(0, sizeOfTextBeforeCursor(page.StartCursor));
		} else {
			int end = sizeOfTextBeforeCursor(page.EndCursor);
			if (end == -1) {
				end = myModel.getTextLength(myModel.getParagraphsNumber() - 1) - 1;
			}
			return Math.max(1, end);
		}
	}

	@Override
	public final synchronized int getScrollbarFullSize() {
		return sizeOfFullText();
	}

	@Override
	public final synchronized int getScrollbarThumbPosition(PageIndex pageIndex) {
		return scrollbarType() == SCROLLBAR_SHOW_AS_PROGRESS ? 0 : getCurrentCharNumber(pageIndex, true);
	}

	@Override
	public final synchronized int getScrollbarThumbLength(PageIndex pageIndex) {
		int start = scrollbarType() == SCROLLBAR_SHOW_AS_PROGRESS
			? 0 : getCurrentCharNumber(pageIndex, true);
		int end = getCurrentCharNumber(pageIndex, false);
		return Math.max(1, end - start);
	}

	private int sizeOfTextBeforeCursor(ZLTextWordCursor wordCursor) {
		final ZLTextParagraphCursor paragraphCursor = wordCursor.getParagraphCursor();
		if (paragraphCursor == null) {
			return -1;
		}
		final int paragraphIndex = paragraphCursor.Index;
		int sizeOfText = myModel.getTextLength(paragraphIndex - 1);
		final int paragraphLength = paragraphCursor.getParagraphLength();
		if (paragraphLength > 0) {
			sizeOfText +=
				(myModel.getTextLength(paragraphIndex) - sizeOfText)
				* wordCursor.getElementIndex()
				/ paragraphLength;
		}
		return sizeOfText;
	}

	// Can be called only when (myModel.getParagraphsNumber() != 0)
	private synchronized float computeCharsPerPage() {
		setTextStyle(getTextStyleCollection().getBaseStyle());

		final int textWidth = getTextColumnWidth();
		final int textHeight = getTextAreaHeight();

		final int num = myModel.getParagraphsNumber();
		final int totalTextSize = myModel.getTextLength(num - 1);
		final float charsPerParagraph = ((float)totalTextSize) / num;

		final float charWidth = computeCharWidth();

		final int indentWidth = getElementWidth(ZLTextElement.Indent, 0);
		final float effectiveWidth = textWidth - (indentWidth + 0.5f * textWidth) / charsPerParagraph;
		float charsPerLine = Math.min(effectiveWidth / charWidth,
				charsPerParagraph * 1.2f);

		final int strHeight = getWordHeight() + getContext().getDescent();
		final int effectiveHeight = (int)
			(textHeight -
				(getTextStyle().getSpaceBefore(metrics())
				+ getTextStyle().getSpaceAfter(metrics()) / 2) / charsPerParagraph);
		final int linesPerPage = effectiveHeight / strHeight;

		return charsPerLine * linesPerPage;
	}

	private synchronized int computeTextPageNumber(int textSize) {
		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return 1;
		}

		final float factor = 1.0f / computeCharsPerPage();
		final float pages = textSize * factor;
		return Math.max((int)(pages + 1.0f - 0.5f * factor), 1);
	}

	private static final char[] ourDefaultLetters = "System developers have used modeling languages for decades to specify, visualize, construct, and document systems. The Unified Modeling Language (UML) is one of those languages. UML makes it possible for team members to collaborate by providing a common language that applies to a multitude of different systems. Essentially, it enables you to communicate solutions in a consistent, tool-supported language.".toCharArray();

	private final char[] myLettersBuffer = new char[512];
	private int myLettersBufferLength = 0;
	private ZLTextModel myLettersModel = null;
	private float myCharWidth = -1f;

	private final float computeCharWidth() {
		if (myLettersModel != myModel) {
			myLettersModel = myModel;
			myLettersBufferLength = 0;
			myCharWidth = -1f;

			int paragraph = 0;
			final int textSize = myModel.getTextLength(myModel.getParagraphsNumber() - 1);
			if (textSize > myLettersBuffer.length) {
				paragraph = myModel.findParagraphByTextLength((textSize - myLettersBuffer.length) / 2);
			}
			while (paragraph < myModel.getParagraphsNumber()
					&& myLettersBufferLength < myLettersBuffer.length) {
				final ZLTextParagraph.EntryIterator it = myModel.getParagraph(paragraph++).iterator();
				while (myLettersBufferLength < myLettersBuffer.length && it.next()) {
					if (it.getType() == ZLTextParagraph.Entry.TEXT) {
						final int len = Math.min(it.getTextLength(),
								myLettersBuffer.length - myLettersBufferLength);
						System.arraycopy(it.getTextData(), it.getTextOffset(),
								myLettersBuffer, myLettersBufferLength, len);
						myLettersBufferLength += len;
					}
				}
			}

			if (myLettersBufferLength == 0) {
				myLettersBufferLength = Math.min(myLettersBuffer.length, ourDefaultLetters.length);
				System.arraycopy(ourDefaultLetters, 0, myLettersBuffer, 0, myLettersBufferLength);
			}
		}

		if (myCharWidth < 0f) {
			myCharWidth = computeCharWidth(myLettersBuffer, myLettersBufferLength);
		}
		return myCharWidth;
	}

	private final float computeCharWidth(char[] pattern, int length) {
		return getContext().getStringWidth(pattern, 0, length) / ((float)length);
	}

	public static class PagePosition {
		public int Current;
		public int Total;

		PagePosition(int current, int total) {
			Current = current;
			Total = total;
		}
	}

	public final synchronized PagePosition pagePosition() {
		if(isDjvu()) {
			PAGENO = new PagePosition(Application.DJVUDocument.currentPageIndex + 1, Application.DJVUDocument.getPageCount());
			return PAGENO;
		}
		int current = computeTextPageNumber(getCurrentCharNumber(PageIndex.current, false));
		int total = computeTextPageNumber(sizeOfFullText());

		if (total > 3) {
			PAGENO = new PagePosition(current, total);
			return PAGENO;
		}

		preparePaintInfo(myCurrentPage);
		ZLTextWordCursor cursor = myCurrentPage.StartCursor;
		if (cursor == null || cursor.isNull()) {
			PAGENO = new PagePosition(current, total);
			return PAGENO;
		}

		if (cursor.isStartOfText()) {
			current = 1;
		} else {
			ZLTextWordCursor prevCursor = myPreviousPage.StartCursor;
			if (prevCursor == null || prevCursor.isNull()) {
				preparePaintInfo(myPreviousPage);
				prevCursor = myPreviousPage.StartCursor;
			}
			if (prevCursor != null && !prevCursor.isNull()) {
				current = prevCursor.isStartOfText() ? 2 : 3;
			}
		}

		total = current;
		cursor = myCurrentPage.EndCursor;
		if (cursor == null || cursor.isNull()) {
			PAGENO = new PagePosition(current, total);
			return PAGENO;
		}
		if (!cursor.isEndOfText()) {
			ZLTextWordCursor nextCursor = myNextPage.EndCursor;
			if (nextCursor == null || nextCursor.isNull()) {
				preparePaintInfo(myNextPage);
				nextCursor = myNextPage.EndCursor;
			}
			if (nextCursor != null) {
				total += nextCursor.isEndOfText() ? 1 : 2;
			}
		}

		PAGENO = new PagePosition(current, total);
		return PAGENO;
	}

	public final RationalNumber getProgress() {
		final PagePosition position = pagePosition();
		return RationalNumber.create(position.Current, position.Total);
	}

	public final synchronized void gotoPage(int page) {
		if(Application.Model != null && Application.Model.Book.isDjvu()) {
			Application.DJVUDocument.currentPageIndex = page;
			turnPage(false, ScrollingMode.NO_OVERLAPPING, 0);
			return;
		}
		
		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return;
		}

		final float factor = computeCharsPerPage();
		final float textSize = page * factor;

		int intTextSize = (int) textSize;
		int paragraphIndex = myModel.findParagraphByTextLength(intTextSize);

		if (paragraphIndex > 0 && myModel.getTextLength(paragraphIndex) > intTextSize) {
			--paragraphIndex;
		}
		intTextSize = myModel.getTextLength(paragraphIndex);

		int sizeOfTextBefore = myModel.getTextLength(paragraphIndex - 1);
		while (paragraphIndex > 0 && intTextSize == sizeOfTextBefore) {
			--paragraphIndex;
			intTextSize = sizeOfTextBefore;
			sizeOfTextBefore = myModel.getTextLength(paragraphIndex - 1);
		}

		final int paragraphLength = intTextSize - sizeOfTextBefore;

		final int wordIndex;
		if (paragraphLength == 0) {
			wordIndex = 0;
		} else {
			preparePaintInfo(myCurrentPage);
			final ZLTextWordCursor cursor = new ZLTextWordCursor(myCurrentPage.EndCursor);
			cursor.moveToParagraph(paragraphIndex);
			wordIndex = cursor.getParagraphCursor().getParagraphLength();
		}

		gotoPositionByEnd(paragraphIndex, wordIndex, 0);
	}

	public void gotoHome() {
		final ZLTextWordCursor cursor = getStartCursor();
		if (!cursor.isNull() && cursor.isStartOfParagraph() && cursor.getParagraphIndex() == 0) {
			return;
		}
		gotoPosition(0, 0, 0);
		preparePaintInfo();
	}

	private List<ZLTextHighlighting> findHilites(ZLTextPage page) {
		final LinkedList<ZLTextHighlighting> hilites = new LinkedList<ZLTextHighlighting>();
		if (mySelection.intersects(page)) {
			hilites.add(mySelection);
		}
		synchronized (myHighlightings) {
			for (ZLTextHighlighting h : myHighlightings) {
				if (h.intersects(page)) {
					hilites.add(h);
				}
			}
		}
		return hilites;
	}

	protected abstract ZLPaintContext.ColorAdjustingMode getAdjustingModeForImages();

	private static final char[] SPACE = new char[] { ' ' };
	private void drawTextLine(ZLTextPage page, List<ZLTextHighlighting> hilites, ZLTextLineInfo info, int from, int to) {
		Boolean3 type = Boolean3.UNDEFINED;
		if(myBook != null && myBook.getLanguage() != null && myBook.getLanguage().startsWith("zh")) {
			type = Application.ViewOptions.ShowTranditionalOption.getValue();
		}
		boolean isDay = Application.ViewOptions.getColorProfile().Name.equals(ColorProfile.DAY);
		
		final ZLPaintContext context = getContext();
		final ZLTextParagraphCursor paragraph = info.ParagraphCursor;
		int index = from;
		final int endElementIndex = info.EndElementIndex;
		int charIndex = info.RealStartCharIndex;
		final List<ZLTextElementArea> pageAreas = page.TextElementMap.areas();
		if (to > pageAreas.size()) {
			return;
		}
//		if(info.isFirstParagraphOfSection && !isGuji() && !isDjvu()) {
//			final ZLTextElementArea area = pageAreas.get(index);
//			context.setLineWidth(5);
//			context.drawLine(getLeftMargin() , area.YEnd,
//					getLeftMargin() + page.getTextWidth(), area.YEnd);
//		}
		for (int wordIndex = info.RealStartElementIndex; wordIndex != endElementIndex && index < to; ++wordIndex, charIndex = 0) {
			final ZLTextElement element = paragraph.getElement(wordIndex);
			final ZLTextElementArea area = pageAreas.get(index);
			if (element == area.Element) {
				++index;
				if (area.ChangeStyle) {
					setTextStyle(area.Style);
				}
				final int areaX = area.XStart;
				final int areaY = isGuji()? area.YEnd: area.YEnd - getElementDescent(element) - getTextStyle().getVerticalAlign(metrics());
				if (element instanceof ZLTextWord) {
					ZLTextWord word = ((ZLTextWord) element);
					final ZLTextPosition pos =
						new ZLTextFixedPosition(info.ParagraphCursor.Index, wordIndex, 0);
					final ZLTextHighlighting hl = getWordHilite(pos, hilites);
					final ZLColor hlColor = hl != null ? hl.getForegroundColor() : null;
					drawWord(
						areaX, areaY, word, charIndex, 
						area.ControlKind == FBTextKind.GUJI_SUPERSCRIPT?area.Length:-1, false,
						hlColor != null ? hlColor :
							!isGuji()?getTextColor(getTextStyle().Hyperlink):
							isDay? (area.ControlKind != FBTextKind.GUJI_PARAGRAPHMARK?getTextStyle().getFontColor():context.getBackgroundColor())
									:getTextStyle().getFontColor().getReverseColor(),
						type);
					if(isGuji() && //(area.ControlKind == -1 || area.ControlKind == FBTextKind.REGULAR || area.ControlKind == FBTextKind.GUJI_COMMENT) && 
							area.Length > 1 && 
							context.isShowGujiPunctuation() == GujiPunctuationEnum.judou){
						if(word.Data[word.Offset+1] == '???'||word.Data[word.Offset+1] == '???'||word.Data[word.Offset+1] == '???') {
							context.drawString(getFontSize()*3/4-areaY, areaX+getFontSize(),  new char[]{'???'}, 
									0, 1, false, Boolean3.UNDEFINED);
						} else if(word.Data[word.Offset+1] == '???'||word.Data[word.Offset+1] == '???'||word.Data[word.Offset+1] == '???'||word.Data[word.Offset+1] == '???') {
							context.drawString(getFontSize()*3/4-areaY, areaX+getFontSize(),  new char[]{'???'}, 
									0, 1, false, Boolean3.UNDEFINED);
						}
					}
				} else if (element instanceof ZLTextImageElement) {
					final ZLTextImageElement imageElement = (ZLTextImageElement)element;
					context.drawImage(
						areaX, areaY,
						imageElement.ImageData,
						getTextAreaSize(),
						getScalingType(imageElement),
						getAdjustingModeForImages()
					);
				} else if (element instanceof ZLTextVideoElement) {
					// TODO: draw
					context.setLineColor(getTextColor(ZLTextHyperlink.NO_LINK));
					context.setFillColor(new ZLColor(127, 127, 127));
					final int xStart = area.XStart + 10;
					final int xEnd = area.XEnd - 10;
					final int yStart = area.YStart + 10;
					final int yEnd = area.YEnd - 10;
					context.fillRectangle(xStart, yStart, xEnd, yEnd);
					context.drawLine(xStart, yStart, xStart, yEnd);
					context.drawLine(xStart, yEnd, xEnd, yEnd);
					context.drawLine(xEnd, yEnd, xEnd, yStart);
					context.drawLine(xEnd, yStart, xStart, yStart);
					final int l = xStart + (xEnd - xStart) * 7 / 16;
					final int r = xStart + (xEnd - xStart) * 10 / 16;
					final int t = yStart + (yEnd - yStart) * 2 / 6;
					final int b = yStart + (yEnd - yStart) * 4 / 6;
					final int c = yStart + (yEnd - yStart) / 2;
					context.setFillColor(new ZLColor(196, 196, 196));
					context.fillPolygon(new int[] { l, l, r }, new int[] { t, b, c });
				} else if (element instanceof ExtensionElement) {
					((ExtensionElement)element).draw(context, area);
				} else if (element == ZLTextElement.HSpace || element == ZLTextElement.NBSpace) {
					final int cw = getSpaceWidth();
					for (int len = 0; len < area.XEnd - area.XStart; len += cw) {
						context.drawString(areaX + len, areaY, SPACE, 0, 1, true, Boolean3.UNDEFINED);
					}
				}
			}
		}
		if (index != to) {
			ZLTextElementArea area = pageAreas.get(index++);
			if (area.ChangeStyle) {
				setTextStyle(area.Style);
			}
			final int start = info.StartElementIndex == info.EndElementIndex
				? info.StartCharIndex : 0;
			final int len = info.EndCharIndex - start;
			final ZLTextWord word = (ZLTextWord)paragraph.getElement(info.EndElementIndex);
			final ZLTextPosition pos =
				new ZLTextFixedPosition(info.ParagraphCursor.Index, info.EndElementIndex, 0);
			final ZLTextHighlighting hl = getWordHilite(pos, hilites);
			final ZLColor hlColor = hl != null ? hl.getForegroundColor() : null;
			drawWord(
				area.XStart, isGuji()?area.YEnd : area.YEnd - context.getDescent() - getTextStyle().getVerticalAlign(metrics()),
				word, start, len, area.AddHyphenationSign,
				hlColor != null ? hlColor : 
					isDay?getTextStyle().getFontColor():getTextStyle().getFontColor().getReverseColor(),
				type);
		}
	}

	private ZLTextHighlighting getWordHilite(ZLTextPosition pos, List<ZLTextHighlighting> hilites) {
		for (ZLTextHighlighting h : hilites) {
			if (h.getStartPosition().compareToIgnoreChar(pos) <= 0
				&& pos.compareToIgnoreChar(h.getEndPosition()) <= 0) {
				return h;
			}
		}
		return null;
	}
	
	private void buildInfos(ZLTextPage page, ZLTextWordCursor start, ZLTextWordCursor result) {
		ZLTextWordCursor end = new ZLTextWordCursor(result);
		result.setCursor(start);
		resetTextStyle();
		int textAreaHeight = page.getTextHeight();
		int gujiLineHeight = getGujiLineHeight();
		
		page.LineInfos.clear();
		page.Column0Height = 0;
		boolean nextParagraph;
		ZLTextLineInfo info = null;
		do {
			final ZLTextLineInfo previousInfo = info;// ????????????????????????????????????????????????????????????????????????????????????null???
			resetTextStyle();
			final ZLTextParagraphCursor paragraphCursor = result.getParagraphCursor();
			final int wordIndex = result.getElementIndex();
			// ?????????????????????style????????????
			applyStyleChanges(paragraphCursor, 0, wordIndex);
			info = new ZLTextLineInfo(paragraphCursor, wordIndex, result.getCharIndex(), getTextStyle());
			final int endIndex = info.ParagraphCursorLength;
			boolean isGujiTitle = isGujiCoverParagraph(start) ? true: false;
			
			if(isGuji() && getGujiStyle() == GujiLayoutStyleEnum.baobei) {
				if(PAGENO == null) return;
				final int pageNo = page.PAGENO;
				boolean isOddPage = false;
				if(pageNo % 2 == 1) {
					isOddPage = true;
				}
				if(!isOddPage && result.getParagraphCursor().isFirstParagraphOfSection() && info.EndElementIndex == 0 && info.EndCharIndex == 0) {
					//
					if(page != myPreviousPage || (end.getParagraphCursor().isFirstParagraphOfSection() || end.getParagraphCursor().isEndOfSection())) {
						if(page == myPreviousPage) {
							//??????????????????????????????????????????cusor???????????????????????????????????????????????????????????????section??????
							myPreviousPage.StartCursor.setCursor(end);
							myPreviousPage.EndCursor.setCursor(end);
							if(end.getParagraphCursor().isFirstParagraphOfSection()) {
								myPreviousPage.StartCursor.previousParagraph();
							}
						}
						break;
					}
				}
			}

//			// pass the firstParagraphOfSection to the real textline
//			if(previousInfo != null && previousInfo.isFirstParagraphOfSection && previousInfo.Height == 0) {
//				previousInfo.isFirstParagraphOfSection = false;
//				info.isFirstParagraphOfSection = true;
//			}
			ZLTextLineInfo prevInfoInSameParagraph = previousInfo;
			while (info.EndElementIndex != endIndex) {
				prevInfoInSameParagraph = info;
				info = processTextLine(page, paragraphCursor, info.EndElementIndex, info.EndCharIndex, endIndex, isGuji()?prevInfoInSameParagraph:previousInfo);
//				// pass the firstParagraphOfSection to the end of the real textline
//				if(prevInfoInSameParagraph != null && prevInfoInSameParagraph.isFirstParagraphOfSection) {
//					prevInfoInSameParagraph.isFirstParagraphOfSection = false;
//					info.isFirstParagraphOfSection = true;
//				}
				textAreaHeight -= isGuji()? gujiLineHeight: info.Height + info.Descent;
				if (textAreaHeight < 0 && page.LineInfos.size() > page.Column0Height) {
					if (page.Column0Height == 0 && page.twoColumnView()) {
						textAreaHeight = page.getTextHeight();
						textAreaHeight -= isGuji()? gujiLineHeight: info.Height + info.Descent;
						page.Column0Height = page.LineInfos.size();
					} else {
						break;
					}
				}
				textAreaHeight -= info.VSpaceAfter;
				result.moveTo(info.EndElementIndex, info.EndCharIndex);
				page.LineInfos.add(info);
				if (textAreaHeight < 0) {
					if (page.Column0Height == 0 && page.twoColumnView()) {
						textAreaHeight = page.getTextHeight();
						page.Column0Height = page.LineInfos.size();
					} else {
						break;
					}
				}
			}
			
			if(isGujiTitle) {
				result.nextParagraph();
				break;
			} 
			nextParagraph = result.isEndOfParagraph() && result.nextParagraph();
			if (nextParagraph && result.getParagraphCursor().isEndOfSection()) {
				if (page.Column0Height == 0 && page.twoColumnView() && !page.LineInfos.isEmpty()) {
					textAreaHeight = page.getTextHeight();
					page.Column0Height = page.LineInfos.size();
				}
			}
		} while (
//				result.isEndOfParagraph() && result.nextParagraph() && !result.getParagraphCursor().isEndOfSection() 
//				&& (textAreaHeight >= 0));
				nextParagraph && textAreaHeight >= 0 &&
				 (!result.getParagraphCursor().isEndOfSection() ||
				  page.LineInfos.size() == page.Column0Height)
				);
		resetTextStyle();
	}

	private boolean isHyphenationPossible() {
		return getTextStyleCollection().getBaseStyle().AutoHyphenationOption.getValue()
			&& getTextStyle().allowHyphenations();
	}

	private volatile ZLTextWord myCachedWord;
	private volatile ZLTextHyphenationInfo myCachedInfo;
	private final synchronized ZLTextHyphenationInfo getHyphenationInfo(ZLTextWord word) {
		if (myCachedWord != word) {
			myCachedWord = word;
			myCachedInfo = ZLTextHyphenator.Instance().getInfo(word);
		}
		return myCachedInfo;
	}

	private int getCommentWidth(ArrayList<Integer> lengthList) {
		int length = 0;
		int firstLineWidth = 0;
		int wordCount = lengthList.size();
		if(wordCount == 0) {
			return 0;
		}
		/*for(int i = 0; i< lengthList.size(); i++) {
			length += lengthList.get(i).intValue();
			if(i == (lengthList.size()-1)/2) {
				midIndex = length;
			}
		}*/
		int[] lengthSum = new int[wordCount];
		for(int i = 0;i < wordCount; i++) {
			length +=lengthList.get(i).intValue();
			lengthSum[i] = length;
		}
		int[] lengthDiff = new int[wordCount];
		int smallestDiff = 0;
		int index = 0;
		for(int i = 0;i < wordCount; i++) {
			lengthDiff[i] = Math.abs(length - 2 *lengthSum[i]);
			if(i == 0) smallestDiff = lengthDiff[0];
			if(smallestDiff>=lengthDiff[i]) {
				smallestDiff = lengthDiff[i];
				index = i;
			}
		}
		firstLineWidth = lengthSum[index];
		//int firstLineWidth = getContext().getStringWidth(str, 0, midIndex);
		//int secondLineWidth = getContext().getStringWidth(str, midIndex, length - midIndex);
		int secondLineWidth = 0;
		for(int i = index + 1; i < wordCount; i++) {
			secondLineWidth +=lengthList.get(i).intValue();
		}
		return Math.max(firstLineWidth, secondLineWidth);
	}
	
	private int getCommentMidIndex(ArrayList<Integer> lengthList) {
		int length = 0;
		int wordCount = lengthList.size();
		/*for(int i = 0; i< lengthList.size(); i++) {
			length += lengthList.get(i).intValue();
			if(i == (lengthList.size()-1)/2) {
				midIndex = length;
			}
		}*/
		int[] lengthSum = new int[wordCount];
		for(int i = 0;i < wordCount; i++) {
			length +=lengthList.get(i).intValue();
			lengthSum[i] = length;
		}
		int[] lengthDiff = new int[wordCount];
		int smallestDiff = 0;
		int index = 0;
		for(int i = 0;i < wordCount; i++) {
			lengthDiff[i] = Math.abs(length - 2 *lengthSum[i]);
			if(i == 0) smallestDiff = lengthDiff[0];
			if(smallestDiff>=lengthDiff[i]) {
				smallestDiff = lengthDiff[i];
				index = i;
			}
		}
		return index;
	}

	// ????????????cusor
	private ZLTextLineInfo processTextLine(
		ZLTextPage page,
		ZLTextParagraphCursor paragraphCursor,
		final int startIndex,
		final int startCharIndex,
		final int endIndex,
		ZLTextLineInfo previousInfo
	) {
		final ZLTextLineInfo info = processTextLineInternal(
			page, paragraphCursor, startIndex, startCharIndex, endIndex, previousInfo
		);
		if (info.EndElementIndex == startIndex && info.EndCharIndex == startCharIndex) {
			info.EndElementIndex = paragraphCursor.getParagraphLength();
			info.EndCharIndex = 0;
			// TODO: add error element
		}
		return info;
	}

	private ZLTextLineInfo processTextLineInternal(
		ZLTextPage page,
		ZLTextParagraphCursor paragraphCursor,
		final int startIndex,
		final int startCharIndex,
		final int endIndex,
		ZLTextLineInfo previousInfo
	) {
		final ZLTextLineInfo info = new ZLTextLineInfo(paragraphCursor, startIndex, startCharIndex, getTextStyle());
		final ZLTextLineInfo cachedInfo = myLineInfoCache.get(info);
		if (cachedInfo != null) {
			cachedInfo.adjust(previousInfo);
			applyStyleChanges(paragraphCursor, startIndex, cachedInfo.EndElementIndex);
			return cachedInfo;
		}

		int currentElementIndex = startIndex;
		int currentCharIndex = startCharIndex;
		final boolean isFirstLine = startIndex == 0 && startCharIndex == 0;

		if (isFirstLine) {
			ZLTextElement element = paragraphCursor.getElement(currentElementIndex);
			while (isStyleChangeElement(element)) {
				applyStyleChangeElement(element);
				++currentElementIndex;
				currentCharIndex = 0;
				if (currentElementIndex == endIndex) {
					break;
				}
				element = paragraphCursor.getElement(currentElementIndex);
			}
			info.StartStyle = getTextStyle();
			info.RealStartElementIndex = currentElementIndex;
			info.RealStartCharIndex = currentCharIndex;
		}

		ZLTextStyle storedStyle = getTextStyle();

		final int maxWidth = page.getTextWidth() - storedStyle.getRightIndent(metrics());
		info.LeftIndent = storedStyle.getLeftIndent(metrics());
		if (isFirstLine && storedStyle.getAlignment() != ZLTextAlignmentType.ALIGN_CENTER) {
			info.LeftIndent += isGuji()?0:storedStyle.getFirstLineIndent(metrics());
		}
		if (info.LeftIndent > maxWidth - 20) {
			info.LeftIndent = maxWidth * 3 / 4;
		}

		byte lastOpenControlKind = getLastOpenControlKind(paragraphCursor, currentElementIndex);
		if(!isFirstLine && lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION) {
			if(previousInfo == null || previousInfo.GujiTitleAnnotationPos == 0) {
				ZLTextLineInfo firstLineInfo = null;
				int wordIndex = 0;
				int charIndex = 0;
				resetTextStyle();
				while(wordIndex != endIndex) {
					firstLineInfo = processTextLine(page, paragraphCursor, wordIndex, charIndex, endIndex, null);
					wordIndex = info.EndElementIndex;
					charIndex = info.EndCharIndex;
					if(firstLineInfo.GujiTitleAnnotationPos > 0) {
						break;
					}
				}
				if(previousInfo != null) {
					previousInfo.GujiTitleAnnotationPos = firstLineInfo.GujiTitleAnnotationPos;
				}
				info.LeftIndent = firstLineInfo.GujiTitleAnnotationPos;
				info.GujiTitleAnnotationPos = firstLineInfo.GujiTitleAnnotationPos;
			} else {
				info.LeftIndent = previousInfo.GujiTitleAnnotationPos;
				info.GujiTitleAnnotationPos = previousInfo.GujiTitleAnnotationPos;
			}
		}
		info.Width = info.LeftIndent;

		if (info.RealStartElementIndex == endIndex) {
			info.EndElementIndex = info.RealStartElementIndex;
			info.EndCharIndex = info.RealStartCharIndex;
			return info;
		}

		int newWidth = info.Width;
		int newHeight = info.Height;
		int newDescent = info.Descent;
		boolean wordOccurred = false;
		boolean isVisible = false;
		int lastSpaceWidth = 0;
		int internalSpaceCounter = 0;
		boolean removeLastSpace = false;
		boolean isShouldBreak = false;
		boolean isShowGujiYi = Application.ViewOptions.ShowGujiTranslationOption.getValue();
		boolean isShowGujiZhu = Application.ViewOptions.ShowGujiAnnotationOption.getValue();
		boolean isShowGujiSuperscript = Application.ViewOptions.ShowGujiSuperscriptOption.getValue();
		ArrayList<Integer> gujiAnnolist = new ArrayList<Integer>();
		boolean isNestedInAnno = false;
		
		do {
			ZLTextElement element = paragraphCursor.getElement(currentElementIndex);
			if(isGuji() && (element instanceof ZLTextWord || element == ZLTextElement.HSpace) &&
					(lastOpenControlKind == FBTextKind.GUJI_ANNOTATION
					|| lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION
					|| lastOpenControlKind == FBTextKind.GUJI_TRANSLATION)) {
				if(!isShowGujiYi && lastOpenControlKind == FBTextKind.GUJI_TRANSLATION) {
				} else if(!isShowGujiZhu && (lastOpenControlKind == FBTextKind.GUJI_ANNOTATION || lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION)) {
				} else {
				//newWidth += getElementWidth(element, currentCharIndex);
					if(isFirstLine && lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION) {
						info.GujiTitleAnnotationPos = newWidth;
						if(previousInfo != null) {
							previousInfo.GujiTitleAnnotationPos = newWidth;
						}
					}
					int index = currentElementIndex;
					
					ZLTextElement commentElement = element;
					int charIndex = currentCharIndex;
					int width = 0;
	 				while((commentElement instanceof ZLTextWord || commentElement == ZLTextElement.HSpace) && index < endIndex) {
	 					newHeight = Math.max(newHeight, getElementHeight(commentElement));
	 					newDescent = Math.max(newDescent, getElementDescent(commentElement));
	 					int commentWidth = getElementWidth(commentElement, charIndex);
	 					if(commentWidth != 0) {
	 						gujiAnnolist.add(commentWidth);
	 					}
	 					width = getCommentWidth(gujiAnnolist);
	 					
	 					if (newWidth+width > maxWidth) {
	 						isShouldBreak = true;
	 						currentElementIndex = index;
	 						info.EndElementIndex = currentElementIndex;
	 						storedStyle = getTextStyle();
	 						break;
	 					} else {
	 						info.IsVisible = true;
	 						info.Width = newWidth+width;
	 						if (info.Height < newHeight) {
	 							info.Height = newHeight;
	 						}
	 						if (info.Descent < newDescent) {
	 							info.Descent = newDescent;
	 						}
	 						currentElementIndex = index;
	 						info.EndElementIndex = currentElementIndex;
	 						info.EndCharIndex = charIndex;
	 						info.SpaceCounter = internalSpaceCounter;
	 						storedStyle = getTextStyle();
	 						wordOccurred = true; 
	 						//removeLastSpace = false;
	 					}
						index++;
	 					commentElement = paragraphCursor.getElement(index);
	 					if(commentElement instanceof ZLTextControlElement) {
 							if(((ZLTextControlElement)commentElement).IsStart) {
 								isNestedInAnno = true;
 							}
	 					}
	 					charIndex = 0;
					}//end while
	 				
	 				// Guji's zhu/yi ends.
	 				if((newWidth+width <= maxWidth) && !(commentElement instanceof ZLTextWord)) {
	 					currentElementIndex = index;
						info.EndElementIndex = currentElementIndex;
						storedStyle = getTextStyle();
	 				}
	 				if(!isNestedInAnno) {
	 					newWidth = info.Width;
	 				}
	 				element = paragraphCursor.getElement(currentElementIndex);
	 				if(isShouldBreak) {
	 					break;
	 				}
				}
			} else if(isGuji() && (element instanceof ZLTextWord) &&
					lastOpenControlKind == FBTextKind.GUJI_SUPERSCRIPT) {
				int index = currentElementIndex;
				ZLTextElement commentElement = element;
				int charIndex = currentCharIndex;
				int supwidth = 0;
				int width = 0;
 				while((commentElement instanceof ZLTextWord) && index < endIndex) {
 					if(((ZLTextWord)commentElement).getString().endsWith("|")) {
 						supwidth = width+getContext().getStringHeight();
 						width = 0;
 						//resetTextStyle();
 						setTextStyle(storedStyle);
 					} else {
 						newHeight = Math.max(newHeight, getElementHeight(commentElement));
 	 					newDescent = Math.max(newDescent, getElementDescent(commentElement));
 	 					width += getElementWidth(commentElement, charIndex);
 					}
 					index++;
 					commentElement = paragraphCursor.getElement(index);
 					charIndex = 0;
 				}
 				if(isShowGujiSuperscript) {
 					width = Math.max(supwidth, width);
 				}
 					
				if (newWidth+width > maxWidth) {
					//storedStyle = getTextStyle();
					break;
				} else {
					info.IsVisible = true;
					info.Width = newWidth+width;
					if (info.Height < newHeight) {
						info.Height = newHeight;
					}
					if (info.Descent < newDescent) {
						info.Descent = newDescent;
					}
					currentElementIndex = index;
					info.EndElementIndex = currentElementIndex;
					info.EndCharIndex = charIndex;
					info.SpaceCounter = internalSpaceCounter;
					storedStyle = getTextStyle();
					wordOccurred = true; 
					//removeLastSpace = false;
				}

				newWidth = info.Width;
 				element = paragraphCursor.getElement(currentElementIndex);
			} else if(isGuji() && (element instanceof ZLTextWord) &&
					lastOpenControlKind == FBTextKind.GUJI_PARAGRAPHMARK) {
				int index = currentElementIndex;
				ZLTextElement commentElement = element;
				int charIndex = currentCharIndex;
				int width = 0;
 				while((commentElement instanceof ZLTextWord) && index < endIndex) {
					newHeight = Math.max(newHeight, getElementHeight(commentElement));
 					newDescent = Math.max(newDescent, getElementDescent(commentElement));
 					width += getElementWidth(commentElement, charIndex);
 					index++;
 					commentElement = paragraphCursor.getElement(index);
 					charIndex = 0;
 				}
 				if(!isNestedInAnno) {
 					if(getTextStyle().Parent.getKind() == FBTextKind.GUJI_TITLEANNOTATION ||
							getTextStyle().Parent.getKind() == FBTextKind.GUJI_ANNOTATION ||
							getTextStyle().Parent.getKind() == FBTextKind.GUJI_TRANSLATION) {
						isNestedInAnno = true;
					}
 				}
 				if(isNestedInAnno) {
 					gujiAnnolist.add(width);
 					width = getCommentWidth(gujiAnnolist);
 				}
 					
				if (newWidth+width > maxWidth) {
					//storedStyle = getTextStyle();
					break;
				} else {
					info.IsVisible = true;
					info.Width = newWidth+width;
					if (info.Height < newHeight) {
						info.Height = newHeight;
					}
					if (info.Descent < newDescent) {
						info.Descent = newDescent;
					}
					currentElementIndex = index;
					info.EndElementIndex = currentElementIndex;
					info.EndCharIndex = charIndex;
					info.SpaceCounter = internalSpaceCounter;
					storedStyle = getTextStyle();
					wordOccurred = true; 
					//removeLastSpace = false;
				}

				if(!isNestedInAnno) {
					newWidth = info.Width;
				}
 				element = paragraphCursor.getElement(currentElementIndex);
			} else if(isGuji() && (element instanceof ZLTextWord) &&
					lastOpenControlKind == FBTextKind.GUJI_CR) {
				int width = getElementWidth(element, currentCharIndex);
				info.LeftIndent-= width;
			} else {
				newWidth += getElementWidth(element, currentCharIndex);
			}
			
			newHeight = Math.max(newHeight, getElementHeight(element));
			newDescent = Math.max(newDescent, getElementDescent(element));
			if (element == ZLTextElement.HSpace) {
				if(isGuji() && !isShowGujiYi && lastOpenControlKind == FBTextKind.GUJI_TRANSLATION) {
				} else if(isGuji() && !isShowGujiZhu && (lastOpenControlKind == FBTextKind.GUJI_ANNOTATION || lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION)) {
				} else {
					if (wordOccurred) {
						wordOccurred = false;
						internalSpaceCounter++;
						lastSpaceWidth = getSpaceWidth();
						if(!(info.EndElementIndex == (endIndex -1) && isGuji())) {
							//newWidth += lastSpaceWidth;
						}
					}
				}
			} else if (element == ZLTextElement.NBSpace) {
				wordOccurred = true;
			} else if (element instanceof ZLTextWord) {
				if(isGuji() && !isShowGujiYi && lastOpenControlKind == FBTextKind.GUJI_TRANSLATION) {
				} else if(isGuji() && !isShowGujiZhu && (lastOpenControlKind == FBTextKind.GUJI_ANNOTATION || lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION)) {
				} else {
				wordOccurred = true;
				isVisible = true;
				}
			} else if (element instanceof ZLTextImageElement) {
				wordOccurred = true;
				isVisible = true;
			} else if (element instanceof ZLTextVideoElement) {
				wordOccurred = true;
				isVisible = true;
			} else if (element instanceof ExtensionElement) {
				wordOccurred = true;
				isVisible = true;
			} else if (isStyleChangeElement(element)) {
				applyStyleChangeElement(element);
				lastOpenControlKind = -1;
				if(element instanceof ZLTextControlElement) {
					ZLTextControlElement control = ((ZLTextControlElement)element);
					if(control.IsStart) {
						lastOpenControlKind = control.Kind;
						if(lastOpenControlKind == FBTextKind.GUJI_CR) {
							// leave GUJI_CR in the previous line
							info.EndElementIndex = currentElementIndex+1;
							storedStyle = getTextStyle();
							break;
						}
					} else if(isGuji()) {
						isNestedInAnno = false;
						if(control.Kind == FBTextKind.GUJI_ANNOTATION
								|| control.Kind == FBTextKind.GUJI_TITLEANNOTATION
								|| control.Kind == FBTextKind.GUJI_TRANSLATION) {
									gujiAnnolist.clear();
								}
						lastOpenControlKind = getLastOpenControlKind(paragraphCursor, currentElementIndex+1);
					}
				}
			}
			if (newWidth > maxWidth) {
				if (info.EndElementIndex != startIndex || element instanceof ZLTextWord) {
					if (element == ZLTextElement.HSpace) {
						if(info.EndElementIndex == (endIndex -1) && isGuji()) {
							info.Width = newWidth;
							info.EndElementIndex = currentElementIndex+1;
							removeLastSpace = true;
						}
					}
					break;
				}
			}
			
			ZLTextElement previousElement = element;
			++currentElementIndex;
			currentCharIndex = 0;
			boolean allowBreak = currentElementIndex == endIndex;
			if (!allowBreak) {//indictates whether this line can be broken at this element
				element = paragraphCursor.getElement(currentElementIndex);
				allowBreak =
					previousElement != ZLTextElement.NBSpace &&
					element != ZLTextElement.NBSpace &&
					(!(element instanceof ZLTextWord) || previousElement instanceof ZLTextWord) &&
					!(element instanceof ZLTextImageElement);// &&
					//!(element instanceof ZLTextControlElement);
			}
			if (allowBreak) {
				info.IsVisible = isVisible;
				info.Width = newWidth;
				if (info.Height < newHeight) {
					info.Height = newHeight;
				}
				if (info.Descent < newDescent) {
					info.Descent = newDescent;
				}
				info.EndElementIndex = currentElementIndex;
				info.EndCharIndex = currentCharIndex;
				info.SpaceCounter = internalSpaceCounter;
				storedStyle = getTextStyle();
				removeLastSpace = !wordOccurred && (internalSpaceCounter > 0);
			}
		} while (currentElementIndex < endIndex);

		if (currentElementIndex != endIndex &&
			(isHyphenationPossible() || info.EndElementIndex == startIndex)) {
			ZLTextElement element = paragraphCursor.getElement(currentElementIndex);
			if (element instanceof ZLTextWord) {
				final ZLTextWord word = (ZLTextWord)element;
				newWidth -= getWordWidth(word, currentCharIndex);
				int spaceLeft = maxWidth - newWidth;
				if ((word.Length > 3 && spaceLeft > 2 * getSpaceWidth())
					|| info.EndElementIndex == startIndex) {
					ZLTextHyphenationInfo hyphenationInfo = getHyphenationInfo(word);
					int hyphenationPosition = currentCharIndex;
					int subwordWidth = 0;
					for (int right = word.Length - 1, left = currentCharIndex; right > left; ) {
						final int mid = (right + left + 1) / 2;
						int m1 = mid;
						while (m1 > left && !hyphenationInfo.isHyphenationPossible(m1)) {
							--m1;
						}
						if (m1 > left) {
							final int w = getWordWidth(
								word,
								currentCharIndex,
								m1 - currentCharIndex,
								word.Data[word.Offset + m1 - 1] != '-'
							);
							if (w < spaceLeft) {
								left = mid;
								hyphenationPosition = m1;
								subwordWidth = w;
							} else {
								right = mid - 1;
							}
						} else {
							left = mid;
						}
					}
					if (hyphenationPosition == currentCharIndex && info.EndElementIndex == startIndex) {
						subwordWidth = getWordWidth(word, currentCharIndex, 1, false);
						int right = word.Length == currentCharIndex + 1 ? word.Length : word.Length - 1;
						int left = currentCharIndex + 1;
						while (right > left) {
							final int mid = (right + left + 1) / 2;
							final int w = getWordWidth(
								word,
								currentCharIndex,
								mid - currentCharIndex,
								word.Data[word.Offset + mid - 1] != '-'
							);
							if (w <= spaceLeft) {
								left = mid;
								subwordWidth = w;
							} else {
								right = mid - 1;
							}
						}
						hyphenationPosition = right;
					}
					if (hyphenationPosition > currentCharIndex) {
						info.IsVisible = true;
						info.Width = newWidth + subwordWidth;
						if (info.Height < newHeight) {
							info.Height = newHeight;
						}
						if (info.Descent < newDescent) {
							info.Descent = newDescent;
						}
						info.EndElementIndex = currentElementIndex;
						info.EndCharIndex = hyphenationPosition;
						info.SpaceCounter = internalSpaceCounter;
						storedStyle = getTextStyle();
						removeLastSpace = false;
					}
				}
			}
		}

		if (removeLastSpace) {
			info.Width -= lastSpaceWidth;
			info.SpaceCounter--;
		}

		setTextStyle(storedStyle);

		if (isFirstLine) {
			info.VSpaceBefore = info.StartStyle.getSpaceBefore(metrics());
			if (previousInfo != null) {
				info.PreviousInfoUsed = true;
				info.Height += Math.max(0, info.VSpaceBefore - previousInfo.VSpaceAfter);
			} else {
				info.PreviousInfoUsed = false;
				info.Height += info.VSpaceBefore;
			}
		}
		if (info.isEndOfParagraph() && !isGuji()) {
			info.VSpaceAfter = getTextStyle().getSpaceAfter(metrics());
		}

		if (info.EndElementIndex != endIndex || endIndex == info.ParagraphCursorLength) {
			myLineInfoCache.put(info, info);
		}

		return info;
	}

	private void prepareTextLine(ZLTextPage page, ZLTextLineInfo info, int x, int y, int columnIndex) {
		int startY = y;
		y = Math.min(y + info.Height, getTopMargin() + getGujiTopBankuangWidth() + page.getTextHeight() - 1);

		final ZLPaintContext context = getContext();
		final ZLTextParagraphCursor paragraphCursor = info.ParagraphCursor;

		setTextStyle(info.StartStyle);
		int spaceCounter = info.SpaceCounter;
		int fullCorrection = 0;
		final boolean endOfParagraph = info.isEndOfParagraph();
		boolean wordOccurred = false;
		boolean changeStyle = true;
		
		x += info.LeftIndent;
		final int maxWidth = page.getTextWidth();
		switch (getTextStyle().getAlignment()) {
			case ZLTextAlignmentType.ALIGN_RIGHT:
				x += maxWidth - getTextStyle().getRightIndent(metrics()) - info.Width;
				break;
			case ZLTextAlignmentType.ALIGN_CENTER:
				x += (maxWidth - getTextStyle().getRightIndent(metrics()) - info.Width) / 2;
				break;
			case ZLTextAlignmentType.ALIGN_JUSTIFY:
				if (!endOfParagraph && (paragraphCursor.getElement(info.EndElementIndex) != ZLTextElement.AfterParagraph)) {
					fullCorrection = maxWidth - getTextStyle().getRightIndent(metrics()) - info.Width;
				}
				break;
			case ZLTextAlignmentType.ALIGN_LEFT:
			case ZLTextAlignmentType.ALIGN_UNDEFINED:
				break;
		}

		final ZLTextParagraphCursor paragraph = info.ParagraphCursor;
		final int paragraphIndex = paragraph.Index;
		final int endElementIndex = info.EndElementIndex;
		int charIndex = info.RealStartCharIndex;
		byte lastOpenControlKind = getLastOpenControlKind(paragraphCursor, info.RealStartElementIndex);
		ZLTextElementArea spaceElement = null;
		int commentCount = 0; int startX = 0;int endX = 0;int commentIndex = 0;int midIndex=0;
		boolean isShowGujiYi = Application.ViewOptions.ShowGujiTranslationOption.getValue();
		boolean isShowGujiZhu = Application.ViewOptions.ShowGujiAnnotationOption.getValue();
		boolean isShowGujiSuperscript = Application.ViewOptions.ShowGujiSuperscriptOption.getValue();
		int bottomLine = getBottomLine(page);
		ArrayList<Integer> gujiAnnolist = new ArrayList<Integer>();
		boolean isNestedInAnno = false;
		for (int wordIndex = info.RealStartElementIndex; wordIndex != endElementIndex; ++wordIndex, charIndex = 0) {
			final ZLTextElement element = paragraph.getElement(wordIndex);
			int width = getElementWidth(element, charIndex);
			if (element == ZLTextElement.HSpace && 
					!((lastOpenControlKind == FBTextKind.GUJI_ANNOTATION
					|| lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION
					|| lastOpenControlKind == FBTextKind.GUJI_TRANSLATION) && isGuji())) {
				if (wordOccurred && spaceCounter > 0) {
					final int correction = fullCorrection / spaceCounter;
					final int spaceLength =  correction;//getSpaceWidth() +
					if (getTextStyle().isUnderline()) {
						spaceElement = new ZLTextElementArea(
							paragraphIndex, wordIndex, 0,
							0, // length
							true, // is last in element
							false, // add hyphenation sign
							false, // changed style
							getTextStyle(), element, x, x + spaceLength, y, y, columnIndex, lastOpenControlKind
						);
					} else {
						spaceElement = null;
					}
					x += spaceLength;
					fullCorrection -= correction;
					wordOccurred = false;
					--spaceCounter;
				}
			} else if (element instanceof ZLTextWord || 
					element instanceof ZLTextImageElement || 
					element instanceof ZLTextVideoElement || 
					element instanceof ExtensionElement ||
					(element == ZLTextElement.HSpace && 
					((lastOpenControlKind == FBTextKind.GUJI_ANNOTATION
					|| lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION
					|| lastOpenControlKind == FBTextKind.GUJI_TRANSLATION) && isGuji()))) {
				if((lastOpenControlKind == FBTextKind.GUJI_ANNOTATION
						|| lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION
						|| lastOpenControlKind == FBTextKind.GUJI_TRANSLATION) && isGuji()) {
					if(!isShowGujiYi && lastOpenControlKind == FBTextKind.GUJI_TRANSLATION) {
						changeStyle = false;
						wordOccurred = true;
						continue;
					} else if(!isShowGujiZhu && (lastOpenControlKind == FBTextKind.GUJI_ANNOTATION 
							|| lastOpenControlKind == FBTextKind.GUJI_TITLEANNOTATION)) {
						changeStyle = false;
						wordOccurred = true;
						continue;
					}
					
					if(commentCount == 0) {
						int index = wordIndex;
						int startCharIndex = charIndex;
						ZLTextElement commentElement = element;
						if(!isNestedInAnno) {
							startX = x;
						}
		 				while((commentElement instanceof ZLTextWord || commentElement == ZLTextElement.HSpace) && index < endElementIndex) {
		 					int commentWidth = getElementWidth(commentElement, startCharIndex);
		 					if(commentWidth != 0) {
		 						gujiAnnolist.add(commentWidth);
		 					}
		 					commentCount++;
		 					index++;
		 					commentElement = paragraph.getElement(index);
		 					startCharIndex = 0;
		 					if(commentElement instanceof ZLTextControlElement) {
	 							if(((ZLTextControlElement)commentElement).IsStart && ((ZLTextControlElement)commentElement).Kind == FBTextKind.GUJI_PARAGRAPHMARK) {
	 								isNestedInAnno = true;
	 								index++;
	 								commentElement = paragraph.getElement(index);
	 								commentWidth = 0;
	 								while((commentElement instanceof ZLTextWord) && index < endElementIndex) {
	 									commentWidth += getElementWidth(commentElement, startCharIndex);
	 				 					commentCount++;
	 				 					index++;
	 				 					commentElement = paragraphCursor.getElement(index);
	 				 					startCharIndex = 0;
	 				 				}
	 								if(commentWidth != 0) {
	 									gujiAnnolist.add(commentWidth);
	 								}
	 								index++;
	 								commentElement = paragraph.getElement(index);
	 							}
		 					}
		 				}
		 				midIndex = getCommentMidIndex(gujiAnnolist) + 1;//index is based on 1
					}
					if(width!=0) {
						commentIndex++;
					}
				} else if(lastOpenControlKind == FBTextKind.GUJI_SUPERSCRIPT) {
					if(commentCount == 0) {
						int index = wordIndex;
						int startCharIndex = charIndex;
						ZLTextElement commentElement = element;
		 				while((commentElement instanceof ZLTextWord) && index != endElementIndex) {
		 					if(((ZLTextWord)commentElement).getString().endsWith("|")) {
		 						midIndex = index;
		 						break;
		 					}
		 					commentCount++;
		 					index++;
		 					commentElement = paragraph.getElement(index);
		 					startCharIndex = 0;
		 				}
		 				startX = x;
					}
					commentIndex = 0;
				} else if(lastOpenControlKind == FBTextKind.GUJI_PARAGRAPHMARK) {
					if(commentCount == 0) {
						int index = wordIndex;
						int startCharIndex = charIndex;
						ZLTextElement commentElement = element;
						int commentWidth = 0;
						while((commentElement instanceof ZLTextWord) && index < endElementIndex) {
							commentWidth += getElementWidth(commentElement, startCharIndex);
		 					commentCount++;
		 					index++;
		 					commentElement = paragraphCursor.getElement(index);
		 					startCharIndex = 0;
		 				}
						if(!isNestedInAnno) {
							startX = x;		 
							if(getTextStyle().Parent.getKind() == FBTextKind.GUJI_TITLEANNOTATION ||
									getTextStyle().Parent.getKind() == FBTextKind.GUJI_ANNOTATION ||
									getTextStyle().Parent.getKind() == FBTextKind.GUJI_TRANSLATION) {
								isNestedInAnno = true;
								midIndex = 10000;
								gujiAnnolist.add(commentWidth);
							}
						}
		 				int fontSize = this.getFontSize();
                        context.setFillShader(true);
                        //context.setFillColor(ZLColor.GUJI_BLACK);
		 				if(!isNestedInAnno) {
		 					context.fillRoundRectangle(x, startY+1, x+commentWidth, startY+this.getGujiLineHeight()-1, fontSize/4, fontSize/4);
		 				} else {
		 					if(commentIndex +1 > midIndex) {
		 						if(commentIndex == midIndex) {
		 							context.fillRoundRectangle(startX, startY+this.getGujiLineHeight()/2+1, startX+commentWidth, 
			 								startY+this.getGujiLineHeight()-1, fontSize/4, fontSize/4);
		 						} else {
		 							context.fillRoundRectangle(x, startY+this.getGujiLineHeight()/2+1, x+commentWidth, 
			 								startY+this.getGujiLineHeight()-1, fontSize/4, fontSize/4);
		 						}
		 						
		 					} else {
		 						context.fillRoundRectangle(x, startY+1, x+commentWidth, startY+this.getGujiLineHeight()/2-1, fontSize/4, fontSize/4);
		 					}
		 				}
                        context.setFillShader(false);
		 				commentIndex++;
					}
					if(!isNestedInAnno) {
						commentIndex = 0;
					}
				} else {
					commentCount = 0;
					commentIndex = 0;
					startX = x;
					midIndex=0;
				}
				
				int fontSize = this.getFontSize();
				final int height = getElementHeight(element);
				final int descent = getElementDescent(element);
				final int length = element instanceof ZLTextWord ? ((ZLTextWord)element).Length : 
					element == ZLTextElement.HSpace? 1 : 0;
				if (spaceElement != null) {
					page.TextElementMap.add(spaceElement);
					spaceElement = null;
				}

				if(commentIndex > midIndex) {
					if(commentIndex == (midIndex +1)) {
						endX=x;
						x = startX;
					}
					if(lastOpenControlKind == FBTextKind.GUJI_PARAGRAPHMARK) {
						
					}
					y = Math.min(startY+this.getGujiLineHeight()/2+fontSize,//startY+this.getGujiLineHeight()*3/4+fontSize/2,
							bottomLine);
					page.TextElementMap.add(new ZLTextElementArea(
							paragraphIndex, wordIndex, charIndex,
							length - charIndex,
							true, // is last in element
							false, // add hyphenation sign
							changeStyle, getTextStyle(), element,
							x, x + width - 1, y - fontSize + 1, y, columnIndex, lastOpenControlKind
						));
				} else if(commentIndex > 0 ) {
					y = Math.min(startY+this.getGujiLineHeight()/2,//startY+this.getGujiLineHeight()/4+fontSize/2,
							bottomLine);
					page.TextElementMap.add(new ZLTextElementArea(
							paragraphIndex, wordIndex, charIndex,
							length - charIndex,
							true, // is last in element
							false, // add hyphenation sign
							changeStyle, getTextStyle(), element,
							x, x + width - 1, y - fontSize + 1, y, columnIndex,lastOpenControlKind 
						));
				} else {
					if(isGuji()) {
						if(lastOpenControlKind == FBTextKind.GUJI_SUBSCRIPT) {
							y = Math.min(startY + fontSize,
									bottomLine);
						} else if(lastOpenControlKind == FBTextKind.GUJI_SUPERSCRIPT) {
							if(!isShowGujiSuperscript && wordIndex <=midIndex) {
								continue;
							}
							if(wordIndex == midIndex) {
								y = Math.min(startY + this.getFontSize(),
										bottomLine);
								page.TextElementMap.add(new ZLTextElementArea(
										paragraphIndex, wordIndex, 0,
										1,
										true, // is last in element
										false, // add hyphenation sign
										changeStyle, getTextStyle(), element,
										x, x + width - 1, y - height + 1, isGuji()?y:y+descent, columnIndex, lastOpenControlKind
									));
								continue;
							} else if(wordIndex < midIndex) {
								y = Math.min(startY + this.getFontSize(),
										bottomLine);
							} else {
								if(wordIndex == (midIndex + 1)) {
									x = startX;
									setTextStyle(getTextStyle().Parent);
									changeStyle = true;
								}
								y = Math.min(startY+this.getGujiLineHeight()/2+this.getFontSize()/2,
										bottomLine);
							}
						} else {
							y = Math.min(startY+this.getGujiLineHeight()/2+fontSize/2,
									bottomLine);
						}
					}
					page.TextElementMap.add(new ZLTextElementArea(
							paragraphIndex, wordIndex, charIndex,
							length - charIndex,
							true, // is last in element
							false, // add hyphenation sign
							changeStyle, getTextStyle(), element,
							x, x + width - 1, y - fontSize + 1, isGuji()?y:y+descent, columnIndex, lastOpenControlKind
						));
					
					//myContext.drawLine(0, y + descent, 1100, y + descent);
				}
				
				changeStyle = false;
				wordOccurred = true;
			} else if (isStyleChangeElement(element)) {
				commentCount = 0;
				if(!isNestedInAnno) {
					commentIndex = 0;
					midIndex=0;
					startX = x;
				}
				
				applyStyleChangeElement(element);
				changeStyle = true;
				lastOpenControlKind = -1;
				if(element instanceof ZLTextControlElement) {
					ZLTextControlElement control = ((ZLTextControlElement)element);
					if(control.IsStart) {
						lastOpenControlKind = ((ZLTextControlElement)element).Kind; 
					} else if(isGuji()){
						if(isNestedInAnno && control.Kind == FBTextKind.GUJI_PARAGRAPHMARK && gujiAnnolist.size() > 1) {
							commentCount++;
						}
						
						if(control.Kind == FBTextKind.GUJI_ANNOTATION
								|| control.Kind == FBTextKind.GUJI_TITLEANNOTATION
								|| control.Kind == FBTextKind.GUJI_TRANSLATION) {
								isNestedInAnno = false;
									gujiAnnolist.clear();
								}
						lastOpenControlKind = getLastOpenControlKind(paragraphCursor, wordIndex+1);
					}
				}
				if(isGuji()) {
					if(endX != 0 && !isNestedInAnno) {
						x = Math.max(x, endX);
						endX = 0;
					}
				}
			}
			x += width;
		}
		if (!endOfParagraph) {
			final int len = info.EndCharIndex;
			if (len > 0) {
				final int wordIndex = info.EndElementIndex;
				final ZLTextWord word = (ZLTextWord)paragraph.getElement(wordIndex);
				final boolean addHyphenationSign = word.Data[word.Offset + len - 1] != '-';
				final int width = getWordWidth(word, 0, len, addHyphenationSign);
				final int height = getElementHeight(word);
				final int descent = context.getDescent();
				page.TextElementMap.add(
					new ZLTextElementArea(
						paragraphIndex, wordIndex, 0,
						len,
						false, // is last in element
						addHyphenationSign,
						changeStyle, getTextStyle(), word,
						x, x + width - 1, y - height + 1, isGuji()?y:y + descent, columnIndex, lastOpenControlKind
					)
				);
			}
		}
	}

	public synchronized final void turnPage(boolean forward, int scrollingMode, int value) {
		preparePaintInfo(myCurrentPage);
		myPreviousPage.reset();
		myNextPage.reset();
		if (myCurrentPage.PaintState == PaintStateEnum.READY) {
			myCurrentPage.PaintState = forward ? PaintStateEnum.TO_SCROLL_FORWARD : PaintStateEnum.TO_SCROLL_BACKWARD;
			myScrollingMode = scrollingMode;
			myOverlappingValue = value;
		}
	}

	public final synchronized void gotoPosition(ZLTextPosition position) {
		if (position != null) {
			if(isDjvu()) {
				Application.DJVUDocument.currentPageIndex = position.getParagraphIndex();
			} else {
				gotoPosition(position.getParagraphIndex(), position.getElementIndex(), position.getCharIndex());
			}
		}
	}

	public final synchronized void gotoPosition(int paragraphIndex, int wordIndex, int charIndex) {
		if (myModel != null && myModel.getParagraphsNumber() > 0) {
			Application.getViewWidget().reset();
			if(isGuji() && myCurrentPage.PAGENO % 2 == 0 && getGujiStyle() == GujiLayoutStyleEnum.baobei) {
				// make pageno be odd in case of the start of sections on the even pages
				myCurrentPage.PAGENO++;
			}
			myCurrentPage.moveStartCursor(paragraphIndex, wordIndex, charIndex);
			myPreviousPage.reset();
			myNextPage.reset();
			preparePaintInfo(myCurrentPage);
			if (myCurrentPage.isEmptyPage()) {
				turnPage(true, ScrollingMode.NO_OVERLAPPING, 0);
			}
		}
	}

	private final synchronized void gotoPositionByEnd(int paragraphIndex, int wordIndex, int charIndex) {
		if (myModel != null && myModel.getParagraphsNumber() > 0) {
			if(isGuji() && myCurrentPage.PAGENO % 2 == 0 && getGujiStyle() == GujiLayoutStyleEnum.baobei) {
				// make pageno be odd in case of the start of sections on the even pages
				myCurrentPage.PAGENO++;
			}
			myCurrentPage.moveEndCursor(paragraphIndex, wordIndex, charIndex);
			myPreviousPage.reset();
			myNextPage.reset();
			preparePaintInfo(myCurrentPage);
			if (myCurrentPage.isEmptyPage()) {
				turnPage(false, ScrollingMode.NO_OVERLAPPING, 0);
			}
		}
	}

	protected synchronized void preparePaintInfo() {
		myPreviousPage.reset();
		myNextPage.reset();
		preparePaintInfo(myCurrentPage);
	}

	private synchronized void preparePaintInfo(ZLTextPage page) {
		page.setSize(getTextColumnWidth(), getTextAreaHeight(), twoColumnView(), page == myPreviousPage);

		if(isGuji()) {
			GujiPunctuationEnum isShowPunctuation = Application.ViewOptions.ShowGujiPunctuationOption.getValue();
			if(isShowPunctuation != myIsShowPunctuation) {
				myIsShowPunctuation = isShowPunctuation;
				getContext().setShowGujiPunctuationChanged(true);
				rebuildPaintInfo();
			} else {
				getContext().setShowGujiPunctuationChanged(false);
			}
			getContext().setIsShowGujiPunctuation(true, isShowPunctuation);
		} else {
			getContext().setIsShowGujiPunctuation(false, GujiPunctuationEnum.show);
		}
		
		if (page.PaintState == PaintStateEnum.NOTHING_TO_PAINT || page.PaintState == PaintStateEnum.READY) {
			return;
		}
		
		final int oldState = page.PaintState;

		final HashMap<ZLTextLineInfo,ZLTextLineInfo> cache = myLineInfoCache;
		for (ZLTextLineInfo info : page.LineInfos) {
			cache.put(info, info);
		}

		switch (page.PaintState) {
			default:
				break;
			case PaintStateEnum.TO_SCROLL_FORWARD:
				if (!page.EndCursor.isEndOfText()) {
					final ZLTextWordCursor startCursor = new ZLTextWordCursor();
					switch (myScrollingMode) {
						case ScrollingMode.NO_OVERLAPPING:
							break;
						case ScrollingMode.KEEP_LINES:
							page.findLineFromEnd(startCursor, myOverlappingValue);
							break;
						case ScrollingMode.SCROLL_LINES:
							page.findLineFromStart(startCursor, myOverlappingValue);
							if (startCursor.isEndOfParagraph()) {
								startCursor.nextParagraph();
							}
							break;
						case ScrollingMode.SCROLL_PERCENTAGE:
							page.findPercentFromStart(startCursor, myOverlappingValue);
							break;
					}

					if (!startCursor.isNull() && startCursor.samePositionAs(page.StartCursor)) {
						page.findLineFromStart(startCursor, 1);
					}

					if (!startCursor.isNull()) {
						final ZLTextWordCursor endCursor = new ZLTextWordCursor();
						buildInfos(page, startCursor, endCursor);
						if (!page.isEmptyPage() && (myScrollingMode != ScrollingMode.KEEP_LINES || !endCursor.samePositionAs(page.EndCursor))) {
							page.StartCursor.setCursor(startCursor);
							page.EndCursor.setCursor(endCursor);
							break;
						}
					}

					page.StartCursor.setCursor(page.EndCursor);
					buildInfos(page, page.StartCursor, page.EndCursor);
				}
				break;
			case PaintStateEnum.TO_SCROLL_BACKWARD:
				if (!page.StartCursor.isStartOfText()) {
					switch (myScrollingMode) {
						case ScrollingMode.NO_OVERLAPPING:
							page.StartCursor.setCursor(findStartOfPrevousPage(page, page.StartCursor));
							break;
						case ScrollingMode.KEEP_LINES:
						{
							ZLTextWordCursor endCursor = new ZLTextWordCursor();
							page.findLineFromStart(endCursor, myOverlappingValue);
							if (!endCursor.isNull() && endCursor.samePositionAs(page.EndCursor)) {
								page.findLineFromEnd(endCursor, 1);
							}
							if (!endCursor.isNull()) {
								ZLTextWordCursor startCursor = findStartOfPrevousPage(page, endCursor);
								if (startCursor.samePositionAs(page.StartCursor)) {
									page.StartCursor.setCursor(findStartOfPrevousPage(page, page.StartCursor));
								} else {
									page.StartCursor.setCursor(startCursor);
								}
							} else {
								page.StartCursor.setCursor(findStartOfPrevousPage(page, page.StartCursor));
							}
							break;
						}
						case ScrollingMode.SCROLL_LINES:
							page.StartCursor.setCursor(findStart(page, page.StartCursor, SizeUnit.LINE_UNIT, myOverlappingValue));
							break;
						case ScrollingMode.SCROLL_PERCENTAGE:
							page.StartCursor.setCursor(findStart(page, page.StartCursor, SizeUnit.PIXEL_UNIT, page.getTextHeight() * myOverlappingValue / 100));
							break;
					}
					buildInfos(page, page.StartCursor, page.EndCursor);
					if (page.isEmptyPage()) {
						page.StartCursor.setCursor(findStart(page, page.StartCursor, SizeUnit.LINE_UNIT, 1));
						buildInfos(page, page.StartCursor, page.EndCursor);
					}
				}
				break;
			case PaintStateEnum.START_IS_KNOWN:
				if (!page.StartCursor.isNull()) {
					buildInfos(page, page.StartCursor, page.EndCursor);
				}
				break;
			case PaintStateEnum.END_IS_KNOWN:
				if (!page.EndCursor.isNull()) {
					page.StartCursor.setCursor(findStartOfPrevousPage(page, page.EndCursor));
					buildInfos(page, page.StartCursor, page.EndCursor);
				}
				break;
		}
		page.PaintState = PaintStateEnum.READY;
		// TODO: cache?
		myLineInfoCache.clear();

		if (page == myCurrentPage) {
			if (oldState != PaintStateEnum.START_IS_KNOWN) {
				myPreviousPage.reset();
			}
			if (oldState != PaintStateEnum.END_IS_KNOWN) {
				myNextPage.reset();
			}
		}
	}

	public void clearCaches() {
		resetMetrics();
		rebuildPaintInfo();
		Application.getViewWidget().reset();
		myCharWidth = -1;
		clearGujiTextCache();
	}

	protected synchronized void rebuildPaintInfo() {
		myPreviousPage.reset();
		myNextPage.reset();
		if (myCursorManager != null) {
			myCursorManager.evictAll();
		}

		if (myCurrentPage.PaintState != PaintStateEnum.NOTHING_TO_PAINT) {
			myCurrentPage.LineInfos.clear();
			if (!myCurrentPage.StartCursor.isNull()) {
				myCurrentPage.StartCursor.rebuild();
				myCurrentPage.EndCursor.reset();
				myCurrentPage.PaintState = PaintStateEnum.START_IS_KNOWN;
			} else if (!myCurrentPage.EndCursor.isNull()) {
				myCurrentPage.EndCursor.rebuild();
				myCurrentPage.StartCursor.reset();
				myCurrentPage.PaintState = PaintStateEnum.END_IS_KNOWN;
			}
		}

		myLineInfoCache.clear();
	}

	private int infoSize(ZLTextLineInfo info, int unit) {
		return (unit == SizeUnit.PIXEL_UNIT) ? (isGuji()? getGujiLineHeight():info.Height + info.Descent + info.VSpaceAfter) : (info.IsVisible ? 1 : 0);
	}

	private static class ParagraphSize {
		public int Height;
		public int TopMargin;
		public int BottomMargin;
	}

	// get the current paragraph's height
	private ParagraphSize paragraphSize(ZLTextPage page, ZLTextWordCursor cursor, boolean beforeCurrentPosition, int unit) {
		final ParagraphSize size = new ParagraphSize();

		final ZLTextParagraphCursor paragraphCursor = cursor.getParagraphCursor();
		if (paragraphCursor == null) {
			return size;
		}
		final int endElementIndex =
			beforeCurrentPosition ? cursor.getElementIndex() : paragraphCursor.getParagraphLength();

		resetTextStyle();

		int wordIndex = 0;
		int charIndex = 0;
		ZLTextLineInfo info = null;
		while (wordIndex < endElementIndex) {
			final ZLTextLineInfo prev = info;
			info = processTextLine(page, paragraphCursor, wordIndex, charIndex, endElementIndex, prev);
			wordIndex = info.EndElementIndex;
			charIndex = info.EndCharIndex;
			size.Height += infoSize(info, unit);
			if (prev == null) {
				size.TopMargin = info.VSpaceBefore;
			}
			size.BottomMargin = info.VSpaceAfter;
		}

		return size;
	}

	private void skip(ZLTextPage page, ZLTextWordCursor cursor, int unit, int size) {
		final ZLTextParagraphCursor paragraphCursor = cursor.getParagraphCursor();
		if (paragraphCursor == null) {
			return;
		}
		final int endElementIndex = paragraphCursor.getParagraphLength();

		resetTextStyle();
		applyStyleChanges(paragraphCursor, 0, cursor.getElementIndex());

		ZLTextLineInfo info = null;
		while (!cursor.isEndOfParagraph() && size > 0) {
			info = processTextLine(page, paragraphCursor, cursor.getElementIndex(), cursor.getCharIndex(), endElementIndex, info);
			cursor.moveTo(info.EndElementIndex, info.EndCharIndex);
			size -= infoSize(info, unit);
		}
	}

	private ZLTextWordCursor findStartOfPrevousPage(ZLTextPage page, ZLTextWordCursor end) {
		if (twoColumnView()) {
			end = findStart(page, end, SizeUnit.PIXEL_UNIT, page.getTextHeight());
		}
		end = findStart(page, end, SizeUnit.PIXEL_UNIT, page.getTextHeight());
		return end;
	}

	private boolean isGujiAuthorParagraph(ZLTextWordCursor start) {
		if(isGuji() && start != null && start.getParagraphIndex() == 1) {
			ZLTextElement element = start.getElement();
			if(element instanceof ZLTextControlElement) {
				ZLTextControlElement control = (ZLTextControlElement)element;
				if(control.IsStart && control.Kind == FBTextKind.GUJI_AUTHOR) {
					return true;
				}
			}
		}
		return false;
	}
	
	private boolean isGujiCoverParagraph(ZLTextWordCursor start) {
		if(isGuji() && start != null) {
			if(start.getParagraphIndex() == 0 || isGujiAuthorParagraph(start)) {
				return true;
			}
		}
		return false;
	}
	
	private ZLTextWordCursor findStart(ZLTextPage page, ZLTextWordCursor end, int unit, int height) {
		final ZLTextWordCursor start = new ZLTextWordCursor(end);
		ParagraphSize size = paragraphSize(page, start, true, unit);// the cursor will not move
		height -= size.Height;
		boolean positionChanged = !start.isStartOfParagraph();
		start.moveToParagraphStart();
		while (height > 0) {
			final ParagraphSize previousSize = size;
			if (positionChanged && start.getParagraphCursor().isEndOfSection()) {
				break;
			}
			if (!start.previousParagraph()) {// the cursor will move
				break;
			}
			if(isGuji() && start.getParagraphIndex() < 2) {
				break;
			}
			if (!start.getParagraphCursor().isEndOfSection()) {
				positionChanged = true;
			}
			size = paragraphSize(page, start, false, unit);
			height -= size.Height;
			if (previousSize != null) {
				height += Math.min(size.BottomMargin, previousSize.TopMargin);
			}
		}
		// skip the chars which are over the height; the cursor will move
		skip(page, start, unit, -height);

		if (unit == SizeUnit.PIXEL_UNIT) {
			boolean sameStart = start.samePositionAs(end);
			if (!sameStart && start.isEndOfParagraph() && end.isStartOfParagraph()) {
				ZLTextWordCursor startCopy = new ZLTextWordCursor(start);
				startCopy.nextParagraph();
				sameStart = startCopy.samePositionAs(end);
			}
			if (sameStart) {
				start.setCursor(findStart(page, end, SizeUnit.LINE_UNIT, 1));
			}
		}

		return start;
	}

	protected ZLTextElementArea getElementByCoordinates(int x, int y) {
		return myCurrentPage.TextElementMap.binarySearch(x, y);
	}

	public final void outlineRegion(ZLTextRegion region) {
		outlineRegion(region != null ? region.getSoul() : null);
	}

	public final void outlineRegion(ZLTextRegion.Soul soul) {
		myShowOutline = true;
		myOutlinedRegionSoul = soul;
	}

	public void hideOutline() {
		myShowOutline = false;
		Application.getViewWidget().reset();
	}

	private ZLTextRegion getOutlinedRegion(ZLTextPage page) {
		return page.TextElementMap.getRegion(myOutlinedRegionSoul);
	}

	public ZLTextRegion getOutlinedRegion() {
		return getOutlinedRegion(myCurrentPage);
	}

/*
	public void resetRegionPointer() {
		myOutlinedRegionSoul = null;
		myShowOutline = true;
	}
*/

	protected ZLTextHighlighting findHighlighting(int x, int y, int maxDistance) {
		final ZLTextRegion region = findRegion(x, y, maxDistance, ZLTextRegion.AnyRegionFilter);
		if (region == null) {
			return null;
		}
		synchronized (myHighlightings) {
			for (ZLTextHighlighting h : myHighlightings) {
				if (h.getBackgroundColor() != null && h.intersects(region)) {
					return h;
				}
			}
		}
		return null;
	}

	protected ZLTextRegion findRegion(int x, int y, ZLTextRegion.Filter filter) {
		return findRegion(x, y, Integer.MAX_VALUE - 1, filter);
	}

	protected ZLTextRegion findRegion(int x, int y, int maxDistance, ZLTextRegion.Filter filter) {
		return myCurrentPage.TextElementMap.findRegion(x, y, maxDistance, filter);
	}

	protected ZLTextElementAreaVector.RegionPair findRegionsPair(int x, int y, ZLTextRegion.Filter filter) {
		return myCurrentPage.TextElementMap.findRegionsPair(x, y, getColumnIndex(x), filter);
	}

	protected boolean initSelection(int x, int y) {
		y -= getTextStyleCollection().getBaseStyle().getFontSize() / 2;
		if (!mySelection.start(x, y)) {
			return false;
		}
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
		return true;
	}

	public void clearSelection() {
		if (mySelection.clear()) {
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	public ZLTextHighlighting getSelectionHighlighting() {
		return mySelection;
	}

	public int getSelectionStartY() {
		if (mySelection.isEmpty()) {
			return 0;
		}
		final ZLTextElementArea selectionStartArea = mySelection.getStartArea(myCurrentPage);
		if (selectionStartArea != null) {
			return selectionStartArea.YStart;
		}
		if (mySelection.hasPartBeforePage(myCurrentPage)) {
			final ZLTextElementArea firstArea = myCurrentPage.TextElementMap.getFirstArea();
			return firstArea != null ? firstArea.YStart : 0;
		} else {
			final ZLTextElementArea lastArea = myCurrentPage.TextElementMap.getLastArea();
			return lastArea != null ? lastArea.YEnd : 0;
		}
	}

	public int getSelectionEndY() {
		if (mySelection.isEmpty()) {
			return 0;
		}
		final ZLTextElementArea selectionEndArea = mySelection.getEndArea(myCurrentPage);
		if (selectionEndArea != null) {
			return selectionEndArea.YEnd;
		}
		if (mySelection.hasPartAfterPage(myCurrentPage)) {
			final ZLTextElementArea lastArea = myCurrentPage.TextElementMap.getLastArea();
			return lastArea != null ? lastArea.YEnd : 0;
		} else {
			final ZLTextElementArea firstArea = myCurrentPage.TextElementMap.getFirstArea();
			return firstArea != null ? firstArea.YStart : 0;
		}
	}

	public ZLTextPosition getSelectionStartPosition() {
		return mySelection.getStartPosition();
	}

	public ZLTextPosition getSelectionEndPosition() {
		return mySelection.getEndPosition();
	}

	public boolean isSelectionEmpty() {
		return mySelection.isEmpty();
	}

	public ZLTextRegion nextRegion(Direction direction, ZLTextRegion.Filter filter) {
		return myCurrentPage.TextElementMap.nextRegion(getOutlinedRegion(), direction, filter);
	}

	@Override
	public boolean canScroll(PageIndex index) {
		switch (index) {
			default:
				return true;
			case next:
			{
				if(isDjvu()) {
					if(Application.DJVUDocument.currentPageIndex < Application.DJVUDocument.getPageCount() -1) {
						return true;
					} else {
						return false;
					}
				}
				final ZLTextWordCursor cursor = getEndCursor();
				return cursor != null && !cursor.isNull() && !cursor.isEndOfText();
			}
			case previous:
			{
				if(isDjvu()) {
					if(Application.DJVUDocument.currentPageIndex > 0) {
						return true;
					} else {
						return false;
					}
				}
				final ZLTextWordCursor cursor = getStartCursor();
				return cursor != null && !cursor.isNull() && !cursor.isStartOfText();
			}
		}
	}

	ZLTextParagraphCursor cursor(int index) {
		return myCursorManager.get(index);
	}

	protected abstract ExtensionElementManager getExtensionManager();
}
