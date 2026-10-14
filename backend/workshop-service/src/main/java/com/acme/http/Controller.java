package com.acme.http;

/** A group of related routes, usually one feature's endpoints. */
public interface Controller {
    void register(Router router);
}
