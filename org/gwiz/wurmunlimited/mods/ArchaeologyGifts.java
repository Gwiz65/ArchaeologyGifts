/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 * 
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 * 
 * For more information, please refer to <http://unlicense.org/>
*/

package org.gwiz.wurmunlimited.mods;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.ItemTemplatesCreatedListener;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.Versioned;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.modsupport.items.ItemIdParser;

import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.items.NoSuchTemplateException;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public class ArchaeologyGifts
		implements WurmServerMod, Configurable, PreInitable, Versioned, ItemTemplatesCreatedListener {

	private static final String version = "1.0";
	private static Logger logger = Logger.getLogger(ArchaeologyGifts.class.getName());
	private static boolean applyArchBugfixes = true;
	private static boolean addGiftsToArchCache = true;
	private static int giftFragAmount = 10;
	private static List<Integer> giftArrayList = new ArrayList<>();

	@Override
	public void configure(Properties properties) {
		applyArchBugfixes = Boolean.parseBoolean(properties.getProperty("applyArchBugfixes", "true"));
		addGiftsToArchCache = Boolean.parseBoolean(properties.getProperty("addGiftsToArchCache", "true"));
		giftFragAmount = Math.max(3, Math.min(127, Integer.parseInt(properties.getProperty("giftFragAmount", "10"))));

		// parse the gift list
		String[] splitGiftList = properties.getProperty("giftList", "").split("\\s+");
		ItemIdParser itemIdParser = new ItemIdParser();
		for (String giftListItem : splitGiftList) {
			try {
				int num = itemIdParser.parse(giftListItem);
				giftArrayList.add(num);
			} catch (IllegalArgumentException e) {
				logger.log(Level.WARNING,
						"Invalid item [" + giftListItem + "] in gift list.  Skipping [" + giftListItem + "].");
			}
		}
		if (giftArrayList.isEmpty()) {
			addGiftsToArchCache = false;
			logger.log(Level.SEVERE, "Unable to parse gift list from properties file. Disabling archaeology gifts.");
		} else {
			logger.log(Level.INFO, giftArrayList.size() + " gift items parsed from properties file.");
		}
	}

	@Override
	public String getVersion() {
		return version;
	}

	@Override
	public void preInit() {
		ClassPool hookClassPool = HookManager.getInstance().getClassPool();

		// Archaeology bug fixes
		if (applyArchBugfixes) {
			try {
				// fix buggy vanilla code concerning alloy and moon metal fragment chances
				CtClass ctFragmentUtilities = hookClassPool.getCtClass("com.wurmonline.server.items.FragmentUtilities");
				ctFragmentUtilities.getDeclaredMethod("getMetalAlloyMaterial").setBody(
						"{ switch (com.wurmonline.server.Server.rand.nextInt(Math.max(3, 75 - $1))) { case 0: { return 30; "
								+ "} case 1: { return 31; } case 2: { return 96; } default: { return 9; } } }");
				ctFragmentUtilities.getDeclaredMethod("getMetalMoonMaterial").setBody(
						"{ switch (com.wurmonline.server.Server.rand.nextInt(Math.max(2, 90 - $1))) { case 0: { return 67; } "
								+ "case 1: { return 56; } default: { return 57; } } }");

				// fix completely identified "metal" lump fragments that don't combine
				CtClass ctItemBehaviour = hookClassPool.getCtClass("com.wurmonline.server.behaviours.ItemBehaviour");
				ctItemBehaviour.getDeclaredMethod("identifyFragment").instrument(new ExprEditor() {
					public void edit(MethodCall methodCall) throws CannotCompileException {
						if (methodCall.getMethodName().equals("setData1")) {
							methodCall.replace("{ if (target.getRealTemplate().isMetalLump()) "
									+ "{ target.setMaterial((byte) 0); target.sendUpdate(); } $_ = $proceed($$); }");
						}
					}
				});
				logger.log(Level.INFO, "Archaeology bug fixes applied.");
			} catch (CannotCompileException | NotFoundException e) {
				logger.log(Level.SEVERE, "Something went horribly wrong applying archaeology bug fixes!", e);
			}
		}

		// Add gifts to archaeology cache loot tables
		if (addGiftsToArchCache) {
			StringBuilder textToInsert = new StringBuilder(
					"{ com.wurmonline.server.items.FragmentUtilities.justStatues = new int[] { ");
			textToInsert.append(giftArrayList.toString().replaceAll("\\[|\\]", ""));
			textToInsert.append(" }; }");
			try {
				CtConstructor staticInitializer = (hookClassPool
						.getCtClass("com.wurmonline.server.items.FragmentUtilities")).getClassInitializer();
				if (staticInitializer != null) {
					staticInitializer.insertAfter(textToInsert.toString());
					logger.log(Level.INFO,
							giftArrayList.size() + " gift fragments added to archaeology cache loot tables.");
				}
			} catch (NotFoundException | CannotCompileException e) {
				logger.log(Level.SEVERE, "Something went horribly wrong updating archaeology cache loot tables!", e);
			}
		}
	}

	@Override
	public void onItemTemplatesCreated() {
		if (addGiftsToArchCache) {
			try {
				// set cache gift items to droppable & set fragment amounts
				for (int id : giftArrayList) {
					ItemTemplate template = ItemTemplateFactory.getInstance().getTemplate(id);
					ReflectionUtil.setPrivateField(template, ReflectionUtil.getField(ItemTemplate.class, "nodrop"),
							false);
					template.setFragmentAmount(Math.max(giftFragAmount, template.getFragmentAmount()));
				}
				logger.log(Level.INFO, "Updated " + giftArrayList.size() + " archaeology cache gift item templates.");
				logger.log(Level.INFO, "Gift items are now droppable and require at least " + giftFragAmount
						+ " fragments to complete.");
			} catch (IllegalArgumentException | ClassCastException | IllegalAccessException | NoSuchFieldException
					| NoSuchTemplateException e) {
				logger.log(Level.SEVERE, "Something went horribly wrong updating gift item templates!", e);
			}
		}
	}
}
