# Temi Room Occupancy Count

This repository contains an Android application that runs on a Temi robot. It navigates the Temi to a specified room/s where it will take a picture of the contents of said room. That resulting image will then pass through a computer vision model that counts the number of occupants.

## Workflow

- Uses the temi sdk to perform actions on the robot like navigation etc.
- Uses the Camera2 API of Android to capture images.

## Prerequisites

1. Setup Temi robot and add locations of the rooms.
