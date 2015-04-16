package com.github.upelsin.streamProxy;

import java.io.OutputStream;
import java.util.Properties;

/**
 * Created by Alexey Dmitriev <mr.alex.dmitriev@gmail.com> on 25.02.2015.
 */
public interface IOutputStreamFactory {

    SideStream createOutputStream(Properties props);
}
