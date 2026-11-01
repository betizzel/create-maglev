package dev.createmaglev.train;

/** The same server-synchronized lift drives the carriage body, its bogeys and passengers. */
public interface LevitationData {
    float maglev$lift();
    void maglev$lift(float lift);
}
