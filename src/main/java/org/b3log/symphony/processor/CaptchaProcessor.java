/*
 * Symphony - A modern community (forum/BBS/SNS/blog) platform written in Java.
 * Copyright (C) 2012-present, b3log.org
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.symphony.processor;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.b3log.latke.http.Dispatcher;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.renderer.PngRenderer;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.util.Strings;
import org.b3log.symphony.model.Common;
import org.json.JSONObject;
import org.patchca.color.GradientColorFactory;
import org.patchca.color.RandomColorFactory;
import org.patchca.filter.predefined.CurvesRippleFilterFactory;
import org.patchca.font.RandomFontFactory;
import org.patchca.service.Captcha;
import org.patchca.service.ConfigurableCaptchaService;
import org.patchca.word.RandomWordFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Captcha processor.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 3.0.0.0, Feb 11, 2020
 * @since 0.2.2
 */
@Singleton
public class CaptchaProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = LogManager.getLogger(CaptchaProcessor.class);

    /**
     * Key of captcha.
     */
    public static final String CAPTCHA = "captcha";

    /**
     * Captchas.
     */
    public static final Set<String> CAPTCHAS = new HashSet<>();

    /**
     * Captcha length.
     */
    private static final int CAPTCHA_LENGTH = 4;

    /**
     * Captcha chars.
     */
    private static final String CHARS = "acdefhijklmnprstuvwxy234578";

    /**
     * Register request handlers.
     */
    public static void register() {
        final BeanManager beanManager = BeanManager.getInstance();
        final CaptchaProcessor captchaProcessor = beanManager.getReference(CaptchaProcessor.class);

        Dispatcher.get("/captcha", captchaProcessor::get);
        Dispatcher.get("/captcha/login", captchaProcessor::getLoginCaptcha);
    }

    /**
     * Checks whether the specified captcha is invalid.
     *
     * @param captcha the specified captcha
     * @return {@code true} if it is invalid, returns {@code false} otherwise
     */
    public static boolean invalidCaptcha(final String captcha) {
        if (StringUtils.isBlank(captcha) || captcha.length() != CAPTCHA_LENGTH) {
            return true;
        }

        boolean ret = !CaptchaProcessor.CAPTCHAS.contains(captcha);
        if (!ret) {
            CaptchaProcessor.CAPTCHAS.remove(captcha);
        }

        return ret;
    }

    /**
     * Gets captcha.
     *
     * @param context the specified context
     */
    public void get(final RequestContext context) {
        final PngRenderer renderer = new PngRenderer();
        context.setRenderer(renderer);

        try {
            final ConfigurableCaptchaService cs = new ConfigurableCaptchaService();
            if (0.5 < Math.random()) {
                cs.setColorFactory(new GradientColorFactory());
            } else {
                cs.setColorFactory(new RandomColorFactory());
            }
            cs.setFilterFactory(new CurvesRippleFilterFactory(cs.getColorFactory()));
            final RandomWordFactory randomWordFactory = new RandomWordFactory();
            randomWordFactory.setCharacters(CHARS);
            randomWordFactory.setMinLength(CAPTCHA_LENGTH);
            randomWordFactory.setMaxLength(CAPTCHA_LENGTH);
            cs.setWordFactory(randomWordFactory);
            cs.setFontFactory(new RandomFontFactory(getAvaialbeFonts()));

            final Captcha captcha = cs.getCaptcha();
            final String challenge = captcha.getChallenge();
            final BufferedImage bufferedImage = captcha.getImage();

            if (CAPTCHAS.size() > 64) {
                CAPTCHAS.clear();
            }

            CAPTCHAS.add(challenge);

            final Response response = context.getResponse();
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Expires", "0");

            renderImg(renderer, bufferedImage);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);
        }
    }

    /**
     * Gets captcha for login.
     *
     * @param context the specified context
     */
    public void getLoginCaptcha(final RequestContext context) {
        try {
            final Response response = context.getResponse();

            final String userId = context.param(Common.NEED_CAPTCHA);
            if (StringUtils.isBlank(userId)) {
                return;
            }

            final JSONObject wrong = LoginProcessor.WRONG_PWD_TRIES.get(userId);
            if (null == wrong) {
                return;
            }

            if (wrong.optInt(Common.WRON_COUNT) < 3) {
                return;
            }

            final PngRenderer renderer = new PngRenderer();
            context.setRenderer(renderer);

            final ConfigurableCaptchaService cs = new ConfigurableCaptchaService();
            if (0.5 < Math.random()) {
                cs.setColorFactory(new GradientColorFactory());
            } else {
                cs.setColorFactory(new RandomColorFactory());
            }
            cs.setFilterFactory(new CurvesRippleFilterFactory(cs.getColorFactory()));
            final RandomWordFactory randomWordFactory = new RandomWordFactory();
            randomWordFactory.setCharacters(CHARS);
            randomWordFactory.setMinLength(CAPTCHA_LENGTH);
            randomWordFactory.setMaxLength(CAPTCHA_LENGTH);
            cs.setWordFactory(randomWordFactory);
            final Captcha captcha = cs.getCaptcha();
            final String challenge = captcha.getChallenge();
            final BufferedImage bufferedImage = captcha.getImage();

            wrong.put(CAPTCHA, challenge);

            response.setHeader("Pragma", "no-cache");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Expires", "0");

            renderImg(renderer, bufferedImage);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);
        }
    }

    private void renderImg(final PngRenderer renderer, final BufferedImage bufferedImage) throws IOException {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "png", baos);
            final byte[] data = baos.toByteArray();
            renderer.setData(data);
        }
    }

    private static List<String> getAvaialbeFonts() {
        final List<String> ret = new ArrayList<>();

        final GraphicsEnvironment e = GraphicsEnvironment.getLocalGraphicsEnvironment();
        final Font[] fonts = e.getAllFonts();
        for (final Font f : fonts) {
            if (Strings.contains(f.getFontName(), new String[]{"Verdana", "DejaVu Sans Mono", "Tahoma"})) {
                ret.add(f.getFontName());
            }
        }

        final String defaultFontName = new JLabel().getFont().getFontName();
        ret.add(defaultFontName);

        return ret;
    }
}
